package io.tiledb.spark;

import io.tiledb.java.api.*;
import java.net.URI;
import java.util.HashMap;
import java.util.logging.Logger;
import org.apache.spark.sql.execution.vectorized.OnHeapColumnVector;
import org.apache.spark.sql.sources.v2.reader.InputPartitionReader;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.vectorized.ColumnarBatch;

public class TileDBDataReaderPartitionScan implements InputPartitionReader<ColumnarBatch> {

  static Logger log = Logger.getLogger(TileDBDataReaderPartitionScan.class.getName());

  // Query buffer size capacity in bytes (default 512mb)
  static final int QUERY_BUFFER_SIZE = 524288000;

  // array resource URI (dense or sparse)
  private URI arrayURI;

  // spark options
  private TileDBDataSourceOptions options;

  // TileDB API resources for this array parition at the given URI
  private Context ctx;
  private ArraySchema arraySchema;
  private Array array;
  private Query query;

  // Spark schema object associated with this projection (if any) for the query
  private StructType sparkSchema;

  // Spark columnar batch object to return from batch column iterator
  private ColumnarBatch resultBatch;

  // Spark batch column vectors
  private OnHeapColumnVector[] resultVectors;

  // signals columnar batch query termination
  private boolean hasNext;

  public TileDBDataReaderPartitionScan(
      URI uri, TileDBReadSchema schema, TileDBDataSourceOptions options) {
    this.arrayURI = uri;
    this.sparkSchema = schema.getSparkSchema();
    this.options = options;
    this.hasNext = false;
  }

  @Override
  public boolean next() {
    try {
      // first submission initialize the query and see if we can fast fail;
      if (query == null) {
        hasNext = initQuery();
      }
      if (!hasNext) {
        return false;
      }
      boolean prevNext = hasNext;
      query.submit();
      // TODO handle zero result realloc case
      hasNext = query.getQueryStatus() == QueryStatus.TILEDB_INCOMPLETE;
      return prevNext;
    } catch (TileDBError err) {
      throw new RuntimeException(err.getMessage());
    }
  }

  @Override
  public ColumnarBatch get() {
    try {
      int colIdx = 0;
      int nRows = 0;
      // This is a special case for COUNT() on the table where no columns are materialized
      if (sparkSchema.fields().length == 0) {
        // TODO: materialize the first dimension and count the result set size
        try (Domain domain = arraySchema.getDomain();
            Dimension dim = domain.getDimension(0)) {
          nRows = getDimensionColumn(dim.getName(), 0);
        }
      } else {
        // loop over all Spark attributes (DataFrame columns) and copy the query result set
        for (StructField field : sparkSchema.fields()) {
          nRows = getColumnBatch(field, colIdx);
          colIdx++;
        }
      }
      // set the number of rows for the batch result set this enables sharing the columnar batch
      // columns
      // across iterations and the total number of rows allocated will be the high water number of
      // rows over all batches
      resultBatch.setNumRows(nRows);
    } catch (TileDBError err) {
      throw new RuntimeException(err.getMessage());
    }
    return resultBatch;
  }

  @Override
  public void close() {
    if (resultBatch != null) {
      resultBatch.close();
    }
    if (query != null) {
      query.close();
    }
    if (arraySchema != null) {
      arraySchema.close();
    }
    if (array != null) {
      array.close();
    }
    if (ctx != null) {
      ctx.close();
    }
  }

  /**
   * Lazy initialize TileDB Query resources for this partition
   *
   * @return true if there are estimated to be results, false otherwise (fast fail)
   * @throws TileDBError A TileDB exception
   */
  private boolean initQuery() throws TileDBError {
    ctx = new Context(options.getTileDBConfigMap());
    array = new Array(ctx, arrayURI.toString(), QueryType.TILEDB_READ);
    arraySchema = array.getSchema();
    try (Domain domain = arraySchema.getDomain();
        NativeArray nativeSubArray =
            new NativeArray(ctx, 2 * (int) domain.getNDim(), domain.getType())) {

      // TODO: Init with one subarray spanning the domain for now
      HashMap<String, Pair> nonEmptyDomain = array.nonEmptyDomain();
      for (int i = 0; i < domain.getNDim(); i++) {
        try (Dimension dim = domain.getDimension(i)) {
          Pair extent = nonEmptyDomain.get(dim.getName());
          nativeSubArray.setItem(i * 2 + 0, extent.getFirst());
          nativeSubArray.setItem(i * 2 + 1, extent.getSecond());
        }
      }
      // Compute estimate for the max number of buffer elements for the subarray query
      HashMap<String, Pair<Long, Long>> maxBufferElements = array.maxBufferElements(nativeSubArray);

      // Compute an upper bound on the number of results in the subarray
      Long estNumRows =
          maxBufferElements.get(Constants.TILEDB_COORDS).getSecond() / domain.getNDim();

      // For this subarray for this partition there are no results
      if (estNumRows == 0) {
        return false;
      }

      // Create query and set the subarray for this partition
      query = new Query(array, QueryType.TILEDB_READ).setSubarray(nativeSubArray);

      // TODO: allow option to override result layout
      if (arraySchema.isSparse()) {
        // sparse, set to array unordered (fastest)
        query.setLayout(Layout.TILEDB_UNORDERED);
      } else {
        // dense, set to array cell order (fastest)
        query.setLayout(arraySchema.getCellOrder());
      }

      // loop over all attributes and set the query buffers based on the result size estimate
      // the query object handles the lifetime of the allocated (offheap) NativeArrays
      for (StructField field : sparkSchema.fields()) {
        // get the spark column name and match to array schema
        String name = field.name();
        if (domain.hasDimension(name)) {
          // dimension column (coordinate buffer allocation handled at the end)
          continue;
        }
        try (Attribute attr = arraySchema.getAttribute(name)) {
          // attribute is variable length, init the varlen result buffers using the est num offsets
          if (attr.isVar()) {
            int noffsets = maxBufferElements.get(name).getFirst().intValue();
            int nvalues = maxBufferElements.get(name).getSecond().intValue();
            query.setBuffer(
                name,
                new NativeArray(ctx, noffsets, Datatype.TILEDB_UINT64),
                new NativeArray(ctx, nvalues, attr.getType()));
          } else {
            // attribute is fixed length, use the result size estimate for allocation
            int nvalues = maxBufferElements.get(name).getSecond().intValue();
            query.setBuffer(name, new NativeArray(ctx, nvalues, attr.getType()));
          }
        }
      }
      // set the coordinate buffer result buffer
      int ncoords = maxBufferElements.get(Constants.TILEDB_COORDS).getSecond().intValue();
      query.setCoordinates(new NativeArray(ctx, ncoords, domain.getType()));

      // Allocate result set batch based on the estimated (upper bound) number of rows / cells
      resultVectors = OnHeapColumnVector.allocateColumns(estNumRows.intValue(), sparkSchema);
      resultBatch = new ColumnarBatch(resultVectors);
    }
    // est that there are resuts, so perform a read for this partition
    return true;
  }

  /**
   * For a given Spark field name, dispatch between attribute and dimension buffer copying
   *
   * @param field Spark field to copy query result set
   * @param index Spark field index in the projected schmema
   * @return number of values copied into the columnar batch result buffers
   * @throws TileDBError A TileDB exception
   */
  private int getColumnBatch(StructField field, int index) throws TileDBError {
    String name = field.name();
    if (arraySchema.hasAttribute(name)) {
      return getAttributeColumn(name, index);
    } else {
      // ith dimension column, need to special case zipped coordinate buffers
      return getDimensionColumn(name, index);
    }
  }

  /**
   * For a given attribute name, dispatch between variable length and scalar buffer copying
   *
   * @param name Attribute name
   * @param index The Attribute index in the columnar buffer array
   * @return number of values copied into the columnar batch result buffers
   * @throws TileDBError A TileDB exception
   */
  private int getAttributeColumn(String name, int index) throws TileDBError {
    try (Attribute attribute = arraySchema.getAttribute(name)) {
      if (attribute.getCellValNum() > 1) {
        // variable length values added as arrays
        return getVarLengthAttributeColumn(name, attribute, index);
      } else {
        // one value per cell
        return getScalarValueAttributeColumn(name, attribute, index);
      }
    }
  }

  private int getScalarValueAttributeColumn(String name, Attribute attribute, int index)
      throws TileDBError {
    int numValues;
    int bufferLength;
    switch (attribute.getType()) {
      case TILEDB_FLOAT32:
        {
          float[] buff = (float[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putFloats(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_FLOAT64:
        {
          double[] buff = (double[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putDoubles(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT8:
      case TILEDB_CHAR:
        {
          byte[] buff = (byte[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putBytes(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT16:
      case TILEDB_UINT8:
        {
          short[] buff = (short[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putShorts(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT32:
      case TILEDB_UINT16:
        {
          int[] buff = (int[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putInts(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT64:
      case TILEDB_UINT32:
      case TILEDB_UINT64:
        {
          long[] buff = (long[]) query.getBuffer(name);
          bufferLength = buff.length;
          numValues = bufferLength;
          resultVectors[index].reset();
          resultVectors[index].putLongs(0, bufferLength, buff, 0);
          break;
        }
      default:
        {
          throw new TileDBError("Not supported getDomain getType " + attribute.getType());
        }
    }
    return numValues;
  }

  private int getVarLengthAttributeColumn(String name, Attribute attribute, int index)
      throws TileDBError {
    int numValues = 0;
    int bufferLength = 0;
    // reset columnar batch start index
    resultVectors[index].reset();
    resultVectors[index].getChild(0).reset();
    switch (attribute.getType()) {
      case TILEDB_FLOAT32:
        {
          float[] buff = (float[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putFloats(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_FLOAT64:
        {
          double[] buff = (double[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putDoubles(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT8:
      case TILEDB_CHAR:
        // string types that don't require any re-encoding to Spark UTF-8 representation supported
        // for now
      case TILEDB_STRING_ASCII:
      case TILEDB_STRING_UTF8:
        {
          byte[] buff = (byte[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putBytes(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT16:
      case TILEDB_UINT8:
        {
          short[] buff = (short[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putShorts(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT32:
      case TILEDB_UINT16:
        {
          int[] buff = (int[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putInts(0, bufferLength, buff, 0);
          break;
        }
      case TILEDB_INT64:
      case TILEDB_UINT32:
      case TILEDB_UINT64:
        {
          long[] buff = (long[]) query.getBuffer(name);
          bufferLength = buff.length;
          resultVectors[index].getChild(0).reserve(bufferLength);
          resultVectors[index].getChild(0).putLongs(0, bufferLength, buff, 0);
          break;
        }
      default:
        {
          throw new TileDBError("Not supported getDomain getType " + attribute.getType());
        }
    }
    if (attribute.isVar()) {
      // add var length offsets
      long[] offsets = query.getVarBuffer(name);
      // number of bytes per (scalar) element in
      int typeSize = attribute.getType().getNativeSize();
      for (int j = 0; j < offsets.length; j++) {
        // for every variable elgnth value, compute the Spark offset location from the number of
        // bytes / varlen cell
        int length =
            (j == offsets.length - 1)
                ? bufferLength * typeSize - (int) offsets[j]
                : (int) offsets[j + 1] - (int) offsets[j];
        resultVectors[index].putArray(j, ((int) offsets[j]) / typeSize, length / typeSize);
      }
      numValues = offsets.length;
    } else {
      // fixed sized array attribute
      int cellNum = (int) attribute.getCellValNum();
      numValues = bufferLength / cellNum;
      for (int j = 0; j < numValues; j++) {
        resultVectors[index].putArray(j, cellNum * j, cellNum);
      }
    }
    return numValues;
  }

  private int getDimensionColumn(String name, int index) throws TileDBError {
    int numValues = 0;
    int bufferLength = 0;
    try (Domain domain = arraySchema.getDomain()) {
      int ndim = Math.toIntExact(domain.getNDim());
      int dimIdx = 0;
      // map
      for (; dimIdx < ndim; dimIdx++) {
        try (Dimension dim = domain.getDimension(dimIdx)) {
          if (dim.getName().equals(name)) {
            break;
          }
        }
      }
      // perform a strided copy for dimension columnar buffers startng a dimIdx offset (slow path)
      switch (domain.getType()) {
        case TILEDB_FLOAT32:
          {
            float[] coords = (float[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putFloat(row, coords[i]);
              }
            }
            break;
          }
        case TILEDB_FLOAT64:
          {
            double[] coords = (double[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putDouble(row, coords[i]);
              }
            }
            break;
          }
        case TILEDB_INT8:
          {
            byte[] coords = (byte[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putByte(row, coords[i]);
              }
            }
            break;
          }
        case TILEDB_INT16:
        case TILEDB_UINT8:
          {
            short[] coords = (short[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putShort(row, coords[i]);
              }
            }
            break;
          }
        case TILEDB_UINT16:
        case TILEDB_INT32:
          {
            int[] coords = (int[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putInt(row, coords[i]);
              }
            }
            break;
          }
        case TILEDB_INT64:
        case TILEDB_UINT32:
        case TILEDB_UINT64:
          {
            long[] coords = (long[]) query.getCoordinates();
            bufferLength = coords.length;
            numValues = bufferLength / ndim;
            if (resultVectors.length > 0) {
              resultVectors[index].reset();
              for (int i = dimIdx, row = 0; i < bufferLength; i += ndim, row++) {
                resultVectors[index].putLong(row, coords[i]);
              }
            }
            break;
          }
        default:
          {
            throw new TileDBError("Unsupported dimension type for domain " + domain.getType());
          }
      }
    }
    return numValues;
  }
}
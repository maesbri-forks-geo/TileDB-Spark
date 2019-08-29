# TileDB-Spark
[![Build Status](https://travis-ci.com/TileDB-Inc/TileDB-Spark.svg?branch=master)](https://travis-ci.com/TileDB-Inc/TileDB-Spark)

Spark DatasourceV2 to the TileDB storage manager.

Currently works for the latest Spark stable release (v2.4).

## Build / Test

To build and install

```
    git clone git@github.com:TileDB-Inc/TileDB-Spark.git
    cd TileDB-Spark
    ./gradlew assemble
    ./gradlew test
```

This will create a `build/libs/TileDB-Spark-0.0.1-SNAPSHOT.jar` JAR as well as build a TileDB-Java Jar that

### Amazon-Linux / EMR

## Spark Shell

To load the TileDB Spark Datasource reader, 
you need to specify the path to built project jar with `--jars` to upload to the Spark cluster.

    $ spark-shell --jars build/libs/TileDB-Spark-0.0.1-SNAPSHOT.jar,/path/to/TileDB-Java-0.1.7-SNAPSHOT.jar

To read TileDB data to a dataframe in the TileDB format, specify the `format` and `uri` option.
Optionally include the `read_buffer_size` to set the off heap tiledb buffer sizes per attribute (include coordinates).
 
    scala> val sampleDF = spark.read
                               .format("io.tiledb.spark")
                               .option("uri", "file:///path/to/tiledb/array")
                               .option("read_buffer_size", 100*1024*1024)
                               .load()
  
To write to TileDB from an existing dataframe, you need to specify a URI and the column(s) which map to sparse array dimensions.  For now only sparse array writes are supported.

    scala > val sampleDF.write()
                        .format("io.tiledb.spark")
                        .option("uri", "file:///path/to/tiledb/array_new")                          
                        .option("schema.dim.0.name", "rows")
                        .option("schema.dim.1.name", "cols")
                        .option("write_buffer_size", 100*1024*1024)
                        .mode(SaveMode.ErrorIfExists)
                        .save();

## Options

### Read/Write options
* `uri` (required): URI to TileDB sparse or dense array
* `tiledb.` (optional): Set a TileDB config option, ex: `option("tiledb.vfs.num_threads", 4)`.  Multiple tiledb config options can be specified.  See the [full list of configuration options](https://docs.tiledb.io/en/latest/tutorials/config.html?highlight=config#summary-of-parameters).

### Read options
* `order` (optional): Result layout order `"row-major"`/ `"TILEDB_ROW_MAJOR"`, `"col-major"` / `"TILEDB_COL_MAJOR"`, or `"unordered"`/ `"TILEDB_UNORDERED"` (default `"unordered"`).
* `read_buffer_size` (optional): Set the TileDB read buffer size in bytes per attribute/coordinates. Defaults to 10MB
* `allow_read_buffer_realloc` (optional): If the read buffer size is too small allow reallocation. Default: True

### Write options
* `schema.dim.<N>.name` (requried): Specify which of the spark dataframe columns names are dimensions.
* `schema.dim.<N>.min` (optional): Specify the lower bound for the TileDB array schema.
* `schema.dim.<N>.max` (optional): Specify the upper bound for the TileDB array schema.
* `schema.dim.<N>.extent` (optional): Specify the shema dimension domain extent (tile size).
* `schema.capacity` (optional): Specify the sparse array tile capacity.
* `write_buffer_size` (optional): Set the TileDB read buffer size in bytes per attribute/coordinates. Defaults to 10MB

## Semantics

### Type Mapping

TileDB-Spark does not support all of TileDB's datatypes.  

* Currently Integer, Float / Double, and ASCII / UTF-8 strings are supported.
* Because integers are upcasted to the next largest signed datatype expressible in Java (ex. `TILEDB_UINT8` -> Java `Short`),
except for `TILEDB_UINT64` which is not expressible as a numeric primitive in Java.
* TileDB `UINT64` values are casted to Java `Long` integers.  Java provides limited functionality for re-interpreting `Long` values as unsigned `Long`.

### Correctness / Validation

* TileDB-Spark doesn't validate UTF-8 data and is assumed that the written TileDB UTF-8 array data is correctly encoded on write.

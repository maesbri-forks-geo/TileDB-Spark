# TileDB-Spark
[![Build Status](https://travis-ci.com/TileDB-Inc/TileDB-Spark.svg?branch=master)](https://travis-ci.com/TileDB-Inc/TileDB-Spark)

Spark DatasourceV2 to the TileDB storage manager.

Currently works for the latest Spark stable release (v2.4).

## Build / Test

To build and install 
    git clone git@github.com:TileDB-Inc/TileDB-Spark.git
    cd TileDB-Spark
    ./gradlew assemble
    ./gradlew test
    
This will create a `build/libs/TileDB-Spark-0.1.0-SNAPSHOT.jar` JAR as well as build a TileDB-Java Jar that

### Amazon-Linux / EMR

## Spark Shell

To load the TileDB Spark Datasource reader, 
you need to specify the path to built project jar with `--jars` to upload to the Spark cluster.

    $ spark-shell --jars build/libs/TileDB-Spark-0.1.0-SNAPSHOT.jar,/path/to/TileDB-Java-0.1.6.jar

To read TileDB data to a dataframe in the TileDB format, specify the `format` and `uri` option
 
    scala> val sampleDF = spark.read
                               .format("io.tiledb.spark")
                               .option("uri", "file:///path/to/tiledb/array") 
                               .load()
                               
### Options

* `uri` (required): URI to TileDB sparse or dense array
* `tiledb.` (optional): Set a tiledb config option, ex: `option("tiledb.vfs.num_threads", 4)`.  Multiple tiledb config options can be specified.  See the [full list of configuration options](https://docs.tiledb.io/en/latest/tutorials/config.html?highlight=config#summary-of-parameters).

## Semantics

### Type Mapping

TileDB-Spark does not support all of TileDB's datatypes.  

* Currently Integer, Float / Double, and ASCII / UTF-8 strings are supported.
* Because integers are upcasted to the next largest signed datatype expressible in Java (ex. `TILEDB_UINT8` -> Java `Short`),
except for `TILEDB_UINT64` which is not expressible as a numeric primitive in Java.
* TileDB `UINT64` values are casted to Java `Long` integers.  Java provides limited functionality for re-interpreting `Long` values as unsigned `Long`.

### Correctness / Validation

* TileDB-Spark doesn't validate UTF-8 data and is assumed that the written TileDB UTF-8 array data is correctly encoded on write.

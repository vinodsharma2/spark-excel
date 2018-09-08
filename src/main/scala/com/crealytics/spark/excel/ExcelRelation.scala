package com.crealytics.spark.excel

import java.math.BigDecimal
import java.sql.Timestamp
import java.text.SimpleDateFormat

import com.monitorjbl.xlsx.StreamingReader
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.poi.ss.usermodel.{
  Cell,
  CellType,
  DataFormatter,
  DateUtil,
  Sheet,
  Workbook,
  WorkbookFactory,
  Row => SheetRow
}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

case class ExcelRelation(
  location: String,
  sheetName: Option[String],
  useHeader: Boolean,
  treatEmptyValuesAsNulls: Boolean,
  inferSheetSchema: Boolean,
  addColorColumns: Boolean = true,
  userSchema: Option[StructType] = None,
  startColumn: Int = 0,
  endColumn: Int = Int.MaxValue,
  timestampFormat: Option[String] = None,
  maxRowsInMemory: Option[Int] = None,
  excerptSize: Int = 10,
  skipFirstRows: Option[Int] = None,
  workbookPassword: Option[String] = None
)(@transient val sqlContext: SQLContext)
    extends BaseRelation
    with TableScan
    with PrunedScan {

  private val path = new Path(location)

  def extractCells(row: org.apache.poi.ss.usermodel.Row): Vector[Option[Cell]] =
    row.eachCellIterator(startColumn, endColumn).to[Vector]

  private def openWorkbook(): Workbook = {
    val inputStream = FileSystem.get(path.toUri, sqlContext.sparkContext.hadoopConfiguration).open(path)
    maxRowsInMemory
      .map { maxRowsInMem =>
        val builder = StreamingReader
          .builder()
          .rowCacheSize(maxRowsInMem)
          .bufferSize(maxRowsInMem * 1024)
        workbookPassword
          .fold(builder)(password => builder.password(password))
          .open(inputStream)
      }
      .getOrElse(
        workbookPassword
          .fold(WorkbookFactory.create(inputStream))(password => WorkbookFactory.create(inputStream, password))
      )
  }

  lazy val excerpt: List[SheetRow] = {
    val workbook = openWorkbook()
    val sheet = findSheet(workbook, sheetName)
    val sheetIterator = sheet.iterator.asScala
    val rows = skipFirstRows.foldLeft(sheetIterator)(_ drop _).dropWhile(_ == null).take(excerptSize).to[List]
    workbook.close()
    rows
  }

  private def restIterator(wb: Workbook, excerptSize: Int) = {
    val sheet = findSheet(wb, sheetName)
    val i = sheet.iterator.asScala
    i.drop(excerptSize + 1)
    i
  }

  private def dataIterator(workbook: Workbook, firstRowWithData: SheetRow, excerpt: List[SheetRow]) = {
    val init = if (useHeader) excerpt else firstRowWithData :: excerpt
    init.iterator ++ restIterator(workbook, excerpt.size + skipFirstRows.getOrElse(0))
  }

  override val schema: StructType = inferSchema

  val dataFormatter = new DataFormatter()

  val timestampParser = if (timestampFormat.isDefined) {
    Some(new SimpleDateFormat(timestampFormat.get))
  } else {
    None
  }

  private def findSheet(workBook: Workbook, sheetName: Option[String]): Sheet = {
    sheetName
      .map { sn =>
        Option(workBook.getSheet(sn)).getOrElse(throw new IllegalArgumentException(s"Unknown sheet $sn"))
      }
      .getOrElse(workBook.sheetIterator.next)
  }

  override def buildScan: RDD[Row] = buildScan(schema.map(_.name).toArray)

  val columnNameRegex = s"(?s)^(.*?)(_color)?$$".r.unanchored
  private def columnExtractor(column: String): SheetRow => Any = {
    val columnNameRegex(columnName, isColor) = column
    val columnIndex = schema.indexWhere(_.name == columnName)

    val cellExtractor: Cell => Any = if (isColor == null) {
      castTo(_, schema(columnIndex).dataType)
    } else {
      _.getCellStyle.getFillForegroundColorColor match {
        case null => ""
        case c: org.apache.poi.xssf.usermodel.XSSFColor => c.getARGBHex
        case c => throw new RuntimeException(s"Unknown color type $c: ${c.getClass}")
      }
    }
    { row: SheetRow =>
      val cell = row.getCell(columnIndex + startColumn)
      if (cell == null) {
        null
      } else {
        cellExtractor(cell)
      }
    }
  }

  override def buildScan(requiredColumns: Array[String]): RDD[Row] = {
    val lookups = requiredColumns.map(columnExtractor).to[Vector]
    val workbook = openWorkbook()
    val rows = dataIterator(workbook, excerpt.head, excerpt.tail).flatMap(row => Try(lookups.map(l => l(row))).toOption)
    val result = rows.to[Vector]
    val rdd = sqlContext.sparkContext.parallelize(result.map(Row.fromSeq))
    workbook.close()
    rdd
  }

  private def stringToDouble(value: String): Double = {
    Try(value.toDouble) match {
      case Success(d) => d
      case Failure(_) => Double.NaN
    }
  }

  private def castTo(cell: Cell, castType: DataType): Any = {
    val cellType = cell.getCellType
    if (cellType == CellType.BLANK) {
      return null
    }

    lazy val dataFormatter = new DataFormatter()
    lazy val stringValue =
      cell.getCellType match {
        case CellType.FORMULA =>
          cell.getCachedFormulaResultType match {
            case CellType.STRING => cell.getRichStringCellValue.getString
            case CellType.NUMERIC => cell.getNumericCellValue.toString
            case _ => dataFormatter.formatCellValue(cell)
          }
        case _ => dataFormatter.formatCellValue(cell)
      }
    lazy val numericValue =
      cell.getCellType match {
        case CellType.NUMERIC => cell.getNumericCellValue
        case CellType.STRING => stringToDouble(cell.getStringCellValue)
        case CellType.FORMULA =>
          cell.getCachedFormulaResultType match {
            case CellType.NUMERIC => cell.getNumericCellValue
            case CellType.STRING => stringToDouble(cell.getRichStringCellValue.getString)
          }
      }
    lazy val bigDecimal = new BigDecimal(numericValue)
    castType match {
      case _: ByteType => numericValue.toByte
      case _: ShortType => numericValue.toShort
      case _: IntegerType => numericValue.toInt
      case _: LongType => numericValue.toLong
      case _: FloatType => numericValue.toFloat
      case _: DoubleType => numericValue
      case _: BooleanType => cell.getBooleanCellValue
      case _: DecimalType => if (cellType == CellType.STRING && cell.getStringCellValue == "") null else bigDecimal
      case _: TimestampType =>
        cellType match {
          case CellType.NUMERIC => new Timestamp(DateUtil.getJavaDate(numericValue).getTime)
          case _ => parseTimestamp(stringValue)
        }
      case _: DateType => new java.sql.Date(DateUtil.getJavaDate(numericValue).getTime)
      case _: StringType => stringValue
      case t => throw new RuntimeException(s"Unsupported cast from $cell to $t")
    }
  }

  private def parseTimestamp(stringValue: String): Timestamp = {
    timestampParser match {
      case Some(parser) => new Timestamp(parser.parse(stringValue).getTime)
      case None => Timestamp.valueOf(stringValue)
    }
  }

  private def getSparkType(cell: Option[Cell]): DataType = {
    cell match {
      case Some(c) =>
        c.getCellTypeEnum match {
          case CellType.FORMULA =>
            c.getCachedFormulaResultTypeEnum match {
              case CellType.STRING => StringType
              case CellType.NUMERIC => DoubleType
              case _ => NullType
            }
          case CellType.STRING if c.getStringCellValue == "" => NullType
          case CellType.STRING => StringType
          case CellType.BOOLEAN => BooleanType
          case CellType.NUMERIC => if (DateUtil.isCellDateFormatted(c)) TimestampType else DoubleType
          case CellType.BLANK => NullType
        }
      case None => NullType
    }
  }

  private def parallelize[T : scala.reflect.ClassTag](seq: Seq[T]): RDD[T] = sqlContext.sparkContext.parallelize(seq)

  /**
    * Generates a header from the given row which is null-safe and duplicate-safe.
    */
  protected def makeSafeHeader(row: Array[String], dataTypes: Array[DataType]): Array[StructField] = {
    if (useHeader) {
      val duplicates = {
        val headerNames = row
          .filter(_ != null)
        headerNames.diff(headerNames.distinct).distinct
      }

      val headerNames = row.zipWithIndex.map {
        case (value, index) =>
          if (value == null || value.isEmpty) {
            // When there are empty strings or the, put the index as the suffix.
            s"_c$index"
          } else if (duplicates.contains(value)) {
            // When there are duplicates, put the index as the suffix.
            s"$value$index"
          } else {
            value
          }
      }
      headerNames.zip(dataTypes).map { case (name, dt) => StructField(name, dt, nullable = true) }
    } else {
      dataTypes.zipWithIndex.map {
        case (dt, index) =>
          // Uses default column names, "_c#" where # is its position of fields
          // when header option is disabled.
          StructField(s"_c$index", dt, nullable = true)
      }
    }
  }

  private def inferSchema(): StructType = this.userSchema.getOrElse {
    val rawHeader = extractCells(excerpt.head).map {
      case Some(value) => value.getStringCellValue
      case _ => ""
    }.toArray

    val dataTypes = if (this.inferSheetSchema) {
      val stringsAndCellTypes = excerpt.tail
        .map(r => extractCells(r).map(getSparkType))
      InferSchema(parallelize(stringsAndCellTypes))
    } else {
      // By default fields are assumed to be StringType
      val maxCellsPerRow =
        excerpt.map(_.cellIterator().asScala.size).reduce(math.max)
      (0 until maxCellsPerRow).map(_ => StringType: DataType).toArray
    }
    val fields = makeSafeHeader(rawHeader, dataTypes)
    val baseSchema = StructType(fields)
    if (addColorColumns) {
      fields.foldLeft(baseSchema) { (schema, header) =>
        schema.add(s"${header}_color", StringType, nullable = true)
      }
    } else {
      baseSchema
    }
  }
}

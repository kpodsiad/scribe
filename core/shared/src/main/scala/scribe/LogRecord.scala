package scribe

import scribe.modify.LogModifier
import scribe.output.{CompositeOutput, EmptyOutput, LogOutput, TextOutput}
import scribe.record.SimpleLogRecord
import scribe.util.Time

import scala.annotation.tailrec

trait LogRecord[M] {
  protected var appliedModifierIds = Set.empty[String]

  def level: Level
  def levelValue: Double
  def message: Message[M]
  def loggable: Loggable[M]
  def throwable: Option[Throwable]
  def fileName: String
  def className: String
  def methodName: Option[String]
  def line: Option[Int]
  def column: Option[Int]
  def thread: Thread
  def data: Map[String, () => Any]
  def timeStamp: Long

  def logOutput: LogOutput

  def get(key: String): Option[Any] = data.get(key).map(_())

  def boost(booster: Double => Double): LogRecord[M] = copy(value = booster(levelValue))
  def checkModifierId(id: String, add: Boolean = true): Boolean = if (id.isEmpty) {     // Always run blank id
    false
  } else if (appliedModifierIds.contains(id)) {
    true
  } else {
    if (add) {
      appliedModifierIds += id
    }
    false
  }
  def modify(modifier: LogModifier): Option[LogRecord[M]] = if (checkModifierId(modifier.id)) {
    Some(this)
  } else {
    modifier(this)
  }
  @tailrec
  final def modify(modifiers: List[LogModifier]): Option[LogRecord[M]] = if (modifiers.isEmpty) {
    Some(this)
  } else {
    modify(modifiers.head) match {
      case None => None
      case Some(record) => record.modify(modifiers.tail)
    }
  }

  def copy(level: Level = level,
           value: Double = levelValue,
           message: Message[M] = message,
           loggable: Loggable[M] = loggable,
           throwable: Option[Throwable] = throwable,
           fileName: String = fileName,
           className: String = className,
           methodName: Option[String] = methodName,
           line: Option[Int] = line,
           column: Option[Int] = column,
           thread: Thread = thread,
           data: Map[String, () => Any] = data,
           timeStamp: Long = timeStamp): LogRecord[M]

  def dispose(): Unit
}

object LogRecord extends LogRecordCreator {
  var creator: LogRecordCreator = SimpleLogRecord

  override def apply[M](level: Level,
               value: Double,
               message: Message[M],
               loggable: Loggable[M],
               throwable: Option[Throwable],
               fileName: String,
               className: String,
               methodName: Option[String],
               line: Option[Int],
               column: Option[Int],
               thread: Thread = Thread.currentThread(),
               data: Map[String, () => Any] = Map.empty,
               timeStamp: Long = Time()): LogRecord[M] = {
    creator[M](level, value, message, loggable, throwable, fileName, className, methodName, line, column, thread, data, timeStamp)
  }

  def simple(message: String,
             fileName: String,
             className: String,
             methodName: Option[String] = None,
             line: Option[Int] = None,
             column: Option[Int] = None,
             level: Level = Level.Info,
             thread: Thread = Thread.currentThread(),
             data: Map[String, () => Any] = Map.empty,
             timeStamp: Long = Time()): LogRecord[String] = {
    apply[String](
      level = level,
      value = level.value,
      message = new LazyMessage[String](() => message),
      loggable = Loggable.StringLoggable,
      throwable = None,
      fileName = fileName,
      className = className,
      methodName = methodName,
      line = line,
      column = column,
      thread = thread,
      data = data,
      timeStamp = timeStamp
    )
  }

  /**
    * Converts a Throwable to a String representation for output in logging.
    */
  @tailrec
  final def throwable2LogOutput(message: LogOutput,
                                t: Throwable,
                                primaryCause: Boolean = true,
                                b: StringBuilder = new StringBuilder): LogOutput = if (t == None.orNull) {
    EmptyOutput
  } else {
    if (!primaryCause) {
      b.append("Caused by: ")
    }
    b.append(t.getClass.getName)
    if (Option(t.getLocalizedMessage).nonEmpty) {
      b.append(": ")
      b.append(t.getLocalizedMessage)
    }
    b.append(scribe.lineSeparator)
    writeStackTrace(b, t.getStackTrace)
    if (Option(t.getCause).isEmpty) {
      val output = new TextOutput(b.toString())
      if (message == EmptyOutput) {
        output
      } else {
        new CompositeOutput(List(message, new TextOutput(scribe.lineSeparator), output))
      }
    } else {
      throwable2LogOutput(message, t.getCause, primaryCause = false, b = b)
    }
  }

  @tailrec
  private def writeStackTrace(b: StringBuilder, elements: Array[StackTraceElement]): Unit = {
    elements.headOption match {
      case None => // No more elements
      case Some(head) => {
        b.append("\tat ")
        b.append(head.getClassName)
        b.append('.')
        b.append(head.getMethodName)
        b.append('(')
        if (head.getLineNumber == -2) {
          b.append("Native Method")
        } else {
          b.append(head.getFileName)
          if (head.getLineNumber > 0) {
            b.append(':')
            b.append(head.getLineNumber)
          }
        }
        b.append(')')
        b.append(scribe.lineSeparator)
        writeStackTrace(b, elements.tail)
      }
    }
  }
}
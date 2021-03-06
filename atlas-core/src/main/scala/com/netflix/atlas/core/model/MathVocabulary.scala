/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.core.model

import com.netflix.atlas.core.model.DataExpr.AggregateFunction
import com.netflix.atlas.core.stacklang.SimpleWord
import com.netflix.atlas.core.stacklang.StandardVocabulary.Macro
import com.netflix.atlas.core.stacklang.Vocabulary
import com.netflix.atlas.core.stacklang.Word

object MathVocabulary extends Vocabulary {

  import com.netflix.atlas.core.model.ModelExtractors._

  val name: String = "math"

  val dependsOn: List[Vocabulary] = List(DataVocabulary)

  val words: List[Word] = List(
    GroupBy,
    Const,
    Random,
    Time,
    CommonQuery,
    Abs, Negate, Sqrt, PerStep,

    Add, Subtract, Multiply, Divide,
    GreaterThan,
    GreaterThanEqual,
    LessThan,
    LessThanEqual,
    FAdd, FSubtract, FMultiply, FDivide,

    And, Or,

    Sum, Count, Min, Max,

    Macro("avg", List(":dup", ":sum", ":swap", ":count", ":div"), List("name,sps,:eq,(,nf.cluster,),:by")),

    Macro("pct", List(":dup", ":sum", ":div", "100", ":mul"), List("name,sps,:eq,(,nf.cluster,),:by")),

    Macro("dist-avg", List(
        "statistic", "(", "totalTime", "totalAmount", ")", ":in", ":sum",
        "statistic", "count", ":eq", ":sum",
        ":div",
        ":swap", ":cq"
      ),
      List("name,playback.startLatency,:eq")),

    Macro("dist-max", List(
        "statistic", "max", ":eq", ":max",
        ":swap", ":cq"
      ),
      List("name,playback.startLatency,:eq")),

    Macro("dist-stddev", List(
        // N
        "statistic", "count", ":eq", ":sum",

        // sum(x^2)
        "statistic", "totalOfSquares", ":eq", ":sum",

        // N * sum(x^2)
        ":mul",

        // sum(x)
        "statistic", "(", "totalAmount", "totalTime", ")", ":in", ":sum",

        // sum(x)^2
        ":dup", ":mul",

        // N * sum(x^2) - sum(x)^2
        ":sub",

        // N^2
        "statistic", "count", ":eq", ":sum", ":dup", ":mul",

        // v = (N * sum(x^2) - sum(x)^2) / N^2
        ":div",

        // stddev = sqrt(v)
        ":sqrt",

        // Swap and use :cq to apply a common query
        ":swap", ":cq"
      ),
      List("name,playback.startLatency,:eq"))
  )

  case object GroupBy extends SimpleWord {
    override def name: String = "by"

    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case StringListType(_) :: TimeSeriesType(t) :: _ =>
        t.dataExprs.forall(_.isInstanceOf[DataExpr.AggregateFunction])
    }

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case StringListType(keys) :: TimeSeriesType(t) :: stack =>
        val f = t.rewrite {
          case af: AggregateFunction => DataExpr.GroupBy(af, keys)
        }
        f :: stack
    }

    override def summary: String =
      """
        |Apply a common group by to all aggregation functions in the expression.
      """.stripMargin.trim

    override def signature: String = "TimeSeriesExpr keys:List -- TimeSeriesExpr"

    override def examples: List[String] = List("name,sps,:eq,:avg,(,nf.cluster,)")
  }

  case object Const extends SimpleWord {
    override def name: String = "const"

    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case (_: String) :: _ => true
    }

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case (v: String) :: stack => MathExpr.Constant(v.toDouble) :: stack
    }

    override def summary: String =
      """
        |Generates a line where each datapoint is a constant value.
      """.stripMargin.trim

    override def signature: String = "Double -- TimeSeriesExpr"

    override def examples: List[String] = List("42")
  }

  case object Random extends SimpleWord {
    override def name: String = "random"

    protected def matcher: PartialFunction[List[Any], Boolean] = { case _ => true }

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case s => MathExpr.Random :: s
    }

    override def summary: String =
      """
        |Generates a line where each datapoint is a random value between 0.0 and 1.0.
      """.stripMargin.trim

    override def signature: String = " -- TimeSeriesExpr"

    override def examples: List[String] = List("")
  }

  case object Time extends SimpleWord {
    override def name: String = "time"

    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case (_: String) :: _ => true
    }

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case (v: String) :: stack => MathExpr.Time(v) :: stack
    }

    override def summary: String =
      """
        |Generates a line based on the current time. Supported modes are secondOfMinute,
        |secondOfDay, minuteOfHour, minuteOfDay, hourOfDay, dayOfWeek, dayOfMonth, dayOfYear,
        |monthOfYear, yearOfCentury, yearOfEra, seconds (since epoch), or days (since epoch). The
        |mode can also be one of the [ChronoField values]
        |(https://docs.oracle.com/javase/8/docs/api/java/time/temporal/ChronoField.html).
      """.stripMargin.trim

    override def signature: String = "String -- TimeSeriesExpr"

    override def examples: List[String] = List("hourOfDay", "HOUR_OF_DAY")
  }

  case object CommonQuery extends SimpleWord {
    override def name: String = "cq"

    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case (_: Query) :: (_: Expr) :: _ => true
    }

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case (q2: Query) :: (expr: Expr) :: stack =>
        val newExpr = expr.rewrite {
          case q1: Query => Query.And(q1, q2)
        }
        newExpr :: stack
    }

    override def summary: String =
      """
        |Recursively AND a common query to all queries in an expression.
      """.stripMargin.trim

    override def signature: String = "Expr Query -- Expr"

    override def examples: List[String] = List(
      "name,ssCpuUser,:eq,name,DiscoveryStatus_UP,:eq,:mul,nf.app,alerttest,:eq")
  }

  sealed trait UnaryWord extends SimpleWord {
    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case TimeSeriesType(_) :: _ => true
    }

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case TimeSeriesType(t) :: stack => newInstance(t) :: stack
    }

    override def signature: String = "TimeSeriesExpr -- TimeSeriesExpr"

    override def examples: List[String] = List("0", "64", "-64")
  }

  case object Abs extends UnaryWord {
    override def name: String = "abs"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Abs(t)

    override def summary: String =
      """
        |Compute a new time series where each interval has the absolute value of the input time
        |series.
      """.stripMargin.trim
  }

  case object Negate extends UnaryWord {
    override def name: String = "neg"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Negate(t)

    override def summary: String =
      """
        |Compute a new time series where each interval has the negated value of the input time
        |series.
      """.stripMargin.trim
  }

  case object Sqrt extends UnaryWord {
    override def name: String = "sqrt"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Sqrt(t)

    override def summary: String =
      """
        |Compute a new time series where each interval has the square root of the value from the
        |input time series.
      """.stripMargin.trim
  }

  case object PerStep extends UnaryWord {
    override def name: String = "per-step"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.PerStep(t)

    override def summary: String =
      """
        |Converts a line from a rate per second to a rate based on the step size of the graph.
        |This is useful for getting an estimate of the raw number of events for a given
        |interval.
      """.stripMargin.trim
  }

  sealed trait BinaryWord extends SimpleWord {
    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case TimeSeriesType(_) :: TimeSeriesType(_) :: _ => true
    }

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case TimeSeriesType(t2) :: TimeSeriesType(t1) :: stack => newInstance(t1, t2) :: stack
    }

    override def signature: String = "TimeSeriesExpr TimeSeriesExpr -- TimeSeriesExpr"

    override def examples: List[String] = List(
      "name,sps,:eq,42",
      "name,sps,:eq,:sum,name,requestsPerSecond,:eq,:max,(,name,),:by")
  }

  case object Add extends BinaryWord {
    override def name: String = "add"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.Add(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a addNaN b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object Subtract extends BinaryWord {
    override def name: String = "sub"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.Subtract(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a subtractNaN b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object Multiply extends BinaryWord {
    override def name: String = "mul"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.Multiply(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a * b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object Divide extends BinaryWord {
    override def name: String = "div"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.Divide(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a / b)` where `a`
        | and `b` are the corresponding intervals in the input time series. If `a` and `b` are 0,
        | then 0 will be returned for the interval. If only `b` is 0, then NaN will be returned as
        | the value for the interval.
        |
        |Use the [fdiv](math-fdiv) operator to get strict floating point behavior.
      """.stripMargin.trim
  }

  case object GreaterThan extends BinaryWord {
    override def name: String = "gt"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.GreaterThan(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a > b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object GreaterThanEqual extends BinaryWord {
    override def name: String = "ge"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.GreaterThanEqual(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a >= b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object LessThan extends BinaryWord {
    override def name: String = "lt"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.LessThan(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a < b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object LessThanEqual extends BinaryWord {
    override def name: String = "le"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.LessThanEqual(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a <= b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object FAdd extends BinaryWord {
    override def name: String = "fadd"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.FAdd(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a + b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object FSubtract extends BinaryWord {
    override def name: String = "fsub"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.FSubtract(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a - b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object FMultiply extends BinaryWord {
    override def name: String = "fmul"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.FMultiply(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a * b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object FDivide extends BinaryWord {
    override def name: String = "fdiv"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.FDivide(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a / b)` where `a`
        | and `b` are the corresponding intervals in the input time series. If `b` is 0, then
        | NaN will be returned as the value for the interval.
      """.stripMargin.trim
  }

  case object And extends BinaryWord {
    override def name: String = "and"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.And(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a AND b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  case object Or extends BinaryWord {
    override def name: String = "or"

    def newInstance(t1: TimeSeriesExpr, t2: TimeSeriesExpr): TimeSeriesExpr = {
      MathExpr.Or(t1, t2)
    }

    override def summary: String =
      """
        |Compute a new time series where each interval has the value `(a OR b)` where `a`
        | and `b` are the corresponding intervals in the input time series.
      """.stripMargin.trim
  }

  sealed trait AggrWord extends SimpleWord {
    protected def matcher: PartialFunction[List[Any], Boolean] = {
      case (_: TimeSeriesExpr) :: _ => true
    }

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr

    protected def executor: PartialFunction[List[Any], List[Any]] = {
      case DataExpr.GroupBy(a: DataExpr.Sum, _) :: stack if this == Sum => a :: stack
      case DataExpr.GroupBy(a: DataExpr.Min, _) :: stack if this == Min => a :: stack
      case DataExpr.GroupBy(a: DataExpr.Max, _) :: stack if this == Max => a :: stack
      case (a: DataExpr.AggregateFunction) :: stack if this != Count    => a :: stack
      case (t: TimeSeriesExpr) :: stack                                 => newInstance(t) :: stack
    }

    override def signature: String = "TimeSeriesExpr -- TimeSeriesExpr"

    override def examples: List[String] = List(
      "name,sps,:eq,:sum",
      "name,sps,:eq,:max,(,nf.cluster,),:by")
  }

  case object Sum extends AggrWord {
    override def name: String = "sum"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Sum(t)

    override def summary: String =
      """
        |Compute the sum of all the time series that result from the previous expression.
      """.stripMargin.trim
  }

  case object Count extends AggrWord {
    override def name: String = "count"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Count(t)

    override def summary: String =
      """
        |Compute the count of all the time series that result from the previous expression.
      """.stripMargin.trim
  }

  case object Min extends AggrWord {
    override def name: String = "min"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Min(t)

    override def summary: String =
      """
        |Compute the min of all the time series that result from the previous expression.
      """.stripMargin.trim
  }

  case object Max extends AggrWord {
    override def name: String = "max"

    def newInstance(t: TimeSeriesExpr): TimeSeriesExpr = MathExpr.Max(t)

    override def summary: String =
      """
        |Compute the max of all the time series that result from the previous expression.
      """.stripMargin.trim
  }
}

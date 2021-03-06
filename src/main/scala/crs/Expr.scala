package crs

import scalaz.{Functor, Free, Cofree, Traverse, Applicative, Show, Cord, Tree, \/-, -\/}
import matryoshka._
import matryoshka.data._
import matryoshka.implicits._
import matryoshka.patterns._
import scalaz.std.anyVal._
import scalaz.std.string._
import scalaz.syntax.applicative._
import scalaz.syntax.show._

sealed abstract class ExprF[A] extends Product with Serializable

object ExprF {
  case class LiteralF[A](value: Int) extends ExprF[A]
  case class AddF[A](lhs: A, rhs: A) extends ExprF[A]
  case class MultiplyF[A](lhs: A, rhs: A) extends ExprF[A]
  case class SubtractF[A](lhs: A, rhs: A) extends ExprF[A]

  implicit def functorExprF: Functor[ExprF] = new Functor[ExprF] {
    def map[A, B](e: ExprF[A])(f: A => B): ExprF[B] = e match {
      case LiteralF(l) => (LiteralF(l): ExprF[B])
      case AddF(l, r) => AddF(f(l), f(r))
      case MultiplyF(l, r) => MultiplyF(f(l), f(r))
      case SubtractF(l, r) => SubtractF(f(l), f(r))
    }
  }

  implicit def traverseExprF: Traverse[ExprF] = new Traverse[ExprF] {
    def traverseImpl[G[_]: Applicative, A, B](fa: ExprF[A])(f: A => G[B]): G[ExprF[B]] = fa match {
      case LiteralF(v) => (LiteralF(v): ExprF[B]).point[G]
      case AddF(l, r) => (f(l) |@| f(r))(AddF(_, _))
      case MultiplyF(l, r) => (f(l) |@| f(r))(MultiplyF(_, _))
      case SubtractF(l, r) => (f(l) |@| f(r))(SubtractF(_, _))
    }
  }

  implicit def showExprF: Show[ExprF[Unit]] = new Show[ExprF[Unit]] {
    def show[A](a: ExprF[Unit]): Cord = a match {
      case LiteralF(v) => Cord(v.shows)
      case AddF(_, _) => Cord("Add")
      case MultiplyF(_, _) => Cord("Multiply")
      case SubtractF(_, _) => Cord("Subtract")
    }
  }
}

object Expr {
  import ExprF._

  type Expr = Fix[ExprF]
  type TaggedTree = Cofree[ExprF, Integer]

  implicit def showString: Show[String] = Show.showFromToString

  def Literal(v: Int): Expr = Fix(LiteralF(v))
  def Add(l: Expr, r: Expr): Expr = Fix(AddF(l, r))
  def Multiply(l: Expr, r: Expr): Expr = Fix(MultiplyF(l, r))
  def Subtract(l: Expr, r: Expr): Expr = Fix(SubtractF(l, r))

  // checkpoint_05
  def showƒ: Algebra[ExprF, Tree[String]] = {
    case LiteralF(i) => Tree.Leaf(i.shows)
    case AddF(l, r) => Tree.Node("Add", Stream(l, r))
    case MultiplyF(l, r) => Tree.Node("Multiply", Stream(l, r))
    case SubtractF(l, r) => Tree.Node("Subtract", Stream(l, r))
  }

  def show(e: Expr): String =
    e.cata(showƒ).drawTree

  // checkpoint_06
  def evaluateƒ: Algebra[ExprF, Int] = {
    case LiteralF(i)     => i
    case AddF(l, r)      => l + r
    case MultiplyF(l, r) => l * r
    case SubtractF(l, r) => l - r
  }

  def evaluate(e: Expr): Int =
    e.cata(evaluateƒ)

  // checkpoint_07
  // spicing it up
  def transformƒ: Algebra[ExprF, Expr] = {
    case MultiplyF(l, r) => Fix(AddF(l, r))
    case otherwise       => Fix(otherwise)
  }

  def transform(e: Expr): Expr =
    e.cata(transformƒ)

  // checkpoint_08
  def cotransformƒ: Coalgebra[ExprF, Expr] = {
    case Fix(MultiplyF(l, r)) => AddF(l, r)
    case Fix(otherwise) => otherwise
  }

  def cotransform(e: Expr): Expr =
    e.ana[Expr](cotransformƒ)

  // checkpoint_09
  def showAnnƒ: Algebra[EnvT[Int, ExprF, ?], Tree[String]] = _.runEnvT match {
    case (ann, fa) => Tree.Node(ann.shows, Stream(showƒ(fa)))
  }

  def showAnn(e: Cofree[ExprF, Int])(
    implicit R: Recursive.Aux[Cofree[ExprF, Int], EnvT[Int, ExprF, ?]]
  ): String = R.cata(e)(showAnnƒ).drawTree


  def annotateƒ: Coalgebra[EnvT[Int, ExprF, ?], Expr] =
    expr => EnvT.envT(evaluate(expr), expr.unFix)

  def annotate(e: Expr): Cofree[ExprF, Int] =
    e.ana[Cofree[ExprF, Int]][EnvT[Int, ExprF, ?]](annotateƒ)
}

object UnaryFn {
  import Expr.showString

  type UnaryFn = Free[ExprF, Hole]

  def Literal(v: Int): UnaryFn = Free.liftF(ExprF.LiteralF(v))
  def Add(l: UnaryFn, r: UnaryFn): UnaryFn = Free.roll(ExprF.AddF(l, r))
  def Multiply(l: UnaryFn, r: UnaryFn): UnaryFn = Free.roll(ExprF.MultiplyF(l, r))
  def Subtract(l: UnaryFn, r: UnaryFn): UnaryFn = Free.roll(ExprF.SubtractF(l, r))
  def HoleU: UnaryFn = Free.point[ExprF, Hole](Hole)

  def showƒ: Algebra[CoEnv[Hole, ExprF, ?], Tree[String]] = {
    case CoEnv(-\/(h)) => Tree.Leaf("○")
    case CoEnv(\/-(exprf)) => Expr.showƒ(exprf)
  }

  // checkpoint_10
  def show(e: UnaryFn)(
    implicit R: Recursive.Aux[UnaryFn, CoEnv[Hole, ExprF, ?]]
  ): String =
    R.cata(e)(showƒ).drawTree

  // splicing it up
  // checkpoint_11
  def spliceƒ(h: Int): Algebra[CoEnv[Hole, ExprF, ?], Fix[ExprF]] = _.run match {
    case (-\/(hole)) => Expr.Literal(h)
    case (\/-(other)) => Fix(other)
  }

  def splice(h: Int, e: UnaryFn)(
    implicit R: Recursive.Aux[UnaryFn, CoEnv[Hole, ExprF, ?]]
  ): Expr.Expr =
    R.cata(e)(spliceƒ(h))
}

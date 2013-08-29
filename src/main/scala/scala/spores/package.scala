/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala

import scala.language.experimental.macros
import scala.reflect.macros.Context

package object spores {

  /**
   *  Usage:
   *
   *  spore {
   *    val x = outerX
   *    val y = outerY
   *    (p: T) => <body>
   *  }
   *
   *  Check that body only accesses x, y, p, and variables local to (owned by) the
   *  closure.
   */
  def spore[T, R](fun: T => R): Spore[T, R] = macro sporeImpl[T, R]

  def spore[T1, T2, R](fun: (T1, T2) => R): Spore2[T1, T2, R] = macro spore2Impl[T1, T2, R]

  def spore[T1, T2, T3, R](fun: (T1, T2, T3) => R): Spore3[T1, T2, T3, R] = macro spore3Impl[T1, T2, T3, R]

  implicit def mkSpore[T, R](fun: T => R): Spore[T, R] = macro sporeImpl[T, R]

  def delayed[T](body: T): Function0[T] = macro delayedImpl[T]

  def delayedImpl[T: c.WeakTypeTag](c: Context)(body: c.Expr[T]): c.Expr[Function0[T]] = {
    import c.universe._


    // check Spore constraints
    //check(c)(fun.tree)

    reify {
      () => body.splice
    }
  }

  /**
     spore {
       val x = outer
       (y: T) => { ... }
     }
   */
  private def check[C <: Context with Singleton](c: Context)(funTree: c.Tree): Unit = {
    import c.universe._

    val isDebugEnabled = false
    def debug(s: => String): Unit =
      if (isDebugEnabled) println(s)

    // traverse body of `fun` and check that the free vars access only allowed things
    val (validEnv, funLiteral) = funTree match {
      case Block(stmts, expr) =>
        val validVarSyms = stmts.toList flatMap { stmt =>
          stmt match {
            case vd @ ValDef(mods, name, tpt, rhs) => List(vd.symbol)
            case _ =>
              c.error(stmt.pos, "Only val defs allowed at this position")
              List()
          }
        }
        validVarSyms foreach { sym => debug("valid: " + sym) }
        (validVarSyms, expr)

      case expr =>
        (List(), expr)
    }

    val captureDef = typeOf[Spore.type].member(newTermName("capture"))

    funLiteral match {
      case fun @ Function(vparams, body) =>

        var capturedSyms = List[Symbol]()

        def isSymbolValid(s: Symbol): Boolean =
          validEnv.contains(s) ||
          capturedSyms.contains(s) ||
          s.owner == fun.symbol ||
          s.isStatic || {
            c.error(s.pos, "invalid reference to " + s)
            false
          }

        def isStablePath(t: Tree): Boolean = t match {
          case sel @ Select(s, _) =>
            isStablePath(s) && sel.symbol.asTerm.isStable
          case id: Ident =>
            id.symbol.asTerm.isStable
          case _ =>
            false
        }

        // traverse and collect captured symbols
        val collectCapturedTraverser = new Traverser {
          override def traverse(tree: Tree): Unit = tree match {
            case app @ Apply(fun, List(captured)) if (fun.symbol == captureDef) =>
              debug("found capture: " + app)
              if (!isStablePath(captured))
                c.error(captured.pos, "Only stable paths can be captured")
              else
                capturedSyms ::= captured.symbol
            case _ =>
              super.traverse(tree)
          }
        }
        debug("collecting captured symbols")
        collectCapturedTraverser.traverse(body)

        debug(s"checking $body...")
        val traverser = new Traverser {
          override def traverse(tree: Tree) {
            tree match {
              case id: Ident =>
                debug("checking ident " + id)
                isSymbolValid(id.symbol)

              // x.m().s
              case sel @ Select(app: Apply, _) =>
                debug("checking select (app)" + sel)
                if (app.symbol.isStatic) {
                  debug("OK, fun static")
                } else c.error(sel.pos, "the fun is not static")

              case sel @ Select(pre, _) =>
                debug("checking select " + sel)
                isSymbolValid(sel.symbol)

              case _ =>
                super.traverse(tree)
            }
          }
        }

        traverser.traverse(body)
      case _ =>
        c.error(funLiteral.pos, "Incorrect usage of `spore`: function literal expected")
    }
  }


  def sporeImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(fun: c.Expr[T => R]): c.Expr[Spore[T, R]] = {
    import c.universe._

    // check Spore constraints
    check(c)(fun.tree)

    reify {
      new SporeImpl(fun.splice)
    }
  }

  def spore2Impl[T1: c.WeakTypeTag, T2: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(fun: c.Expr[(T1, T2) => R]): c.Expr[Spore2[T1, T2, R]] = {
    import c.universe._

    // check Spore constraints
    check(c)(fun.tree)

    reify {
      new Spore2Impl(fun.splice)
    }
  }

  def spore3Impl[T1: c.WeakTypeTag, T2: c.WeakTypeTag, T3: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(fun: c.Expr[(T1, T2, T3) => R]): c.Expr[Spore3[T1, T2, T3, R]] = {
    import c.universe._

    // check Spore constraints
    check(c)(fun.tree)

    reify {
      new Spore3Impl(fun.splice)
    }
  }


  private def checkTc[C <: Context with Singleton](c: Context)(funTree: c.Tree): List[c.Type] = {
    import c.universe._

    val isDebugEnabled = true
    def debug(s: => String): Unit =
      if (isDebugEnabled) println(s)

    // traverse body of `fun` and check that the free vars access only allowed things
    val (validEnv, funLiteral) = funTree match {
      case Block(stmts, expr) =>
        val validVarSyms: List[(Symbol, Type)] = stmts.toList flatMap { stmt =>
          stmt match {
            case vd @ ValDef(mods, name, tpt, rhs) =>
              List(vd.symbol -> tpt.tpe)
            case td @ TypeDef(mods, name, tparams, rhs) =>
              println(s"found type def $name with rhs: $rhs")
              if (name.toString == "Constraint") {
                rhs match {
                  case tpt @ TypeTree() =>
                    println(s"found TypeTree, tpe: ${tpt.tpe}")
                  case _ =>
                    println(s"rhs is something else: ${rhs.getClass}")
                }
              }
              List()
            case _ =>
              c.error(stmt.pos, "Only val defs allowed at this position")
              List()
          }
        }
        validVarSyms foreach { p => debug("valid: " + p) }
        (validVarSyms, expr)

      case expr =>
        (List(), expr)
    }

    funLiteral match {
      case fun @ Function(vparams, body) =>

        def isSymbolValid(s: Symbol): Boolean =
          validEnv.map(_._1).contains(s) ||
          s.owner == fun.symbol ||
          s.isStatic || {
            c.error(s.pos, "invalid reference to " + s)
            false
          }

        debug(s"checking $body...")
        val traverser = new Traverser {
          override def traverse(tree: Tree) {
            tree match {
              case id: Ident =>
                debug("checking ident " + id)
                isSymbolValid(id.symbol)

              case sel @ Select(app: Apply, _) =>
                debug("checking select (app)" + sel)
                if (app.symbol.isStatic) {
                  debug("OK, fun static")
                } else c.error(sel.pos, "the fun is not static")

              case sel @ Select(pre, _) =>
                debug("checking select " + sel)
                isSymbolValid(sel.symbol)

              case _ =>
                super.traverse(tree)
            }
          }
        }

        traverser.traverse(body)
      case _ =>
        c.error(funLiteral.pos, "Function literal expected")
    }

    validEnv map { _._2 }
  }

  def sporeWith[T, R](fun: T => R): Spore[T, R] = macro sporeTcImpl[T, R]

  def sporeTcImpl[T: c.WeakTypeTag, R: c.WeakTypeTag](c: Context)(fun: c.Expr[T => R]): c.Expr[Spore[T, R]] = {
    import c.universe._

    // check Spore constraints
    val tpes = checkTc(c)(fun.tree)
    println("captured types: " + tpes)

    reify {
      val f = fun.splice
      new Spore[T, R] {
        def apply(x: T): R = f(x)
      }
    }
  }

}
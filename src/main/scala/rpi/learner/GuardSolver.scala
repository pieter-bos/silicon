package rpi.learner

import rpi._
import rpi.util.Expressions
import viper.silver.{ast => sil}

class GuardSolver(learner: Learner, constraints: sil.Exp) {
  private lazy val model = {
    val solver = learner.solver
    solver.solve(constraints)
  }

  private lazy val fields = {
    val program = learner.inference.program
    program.fields.map { field => field.name -> field }.toMap
  }

  def solveTemplate(template: Template): sil.Exp = {
    val atoms = template.specification.atoms
    val conjuncts = template.accesses.map { resource => createGuarded(resource, atoms) }
    Expressions.simplify(Expressions.bigAnd(conjuncts))
  }

  private def createGuarded(guarded: Guarded, atoms: Seq[sil.Exp]): sil.Exp = {
    val guard = {
      val id = guarded.id
      val clauses = for (j <- 0 until Config.maxClauses) yield {
        val clauseActivation = model.getOrElse(s"x_${id}_$j", false)
        if (clauseActivation) {
          val literals = atoms.zipWithIndex.map {
            case (atom, i) => model
              .get(s"y_${id}_${i}_$j")
              .flatMap { literalActivation =>
                if (literalActivation) model
                  .get(s"s_${id}_${i}_$j")
                  .map { sign => if (sign) atom else sil.Not(atom)() }
                else None
              }
              .getOrElse(sil.TrueLit()())
          }
          Expressions.bigAnd(literals)
        } else sil.FalseLit()()
      }
      Expressions.bigOr(clauses)
    }

    val resource = guarded.access match {
      case access: sil.FieldAccess =>
        sil.FieldAccessPredicate(access, sil.FullPerm()())()
      case access: sil.PredicateAccess =>
        sil.PredicateAccessPredicate(access, sil.FullPerm()())()
    }

    sil.Implies(guard, resource)()
  }
}

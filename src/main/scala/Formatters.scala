package semper
package silicon

import interfaces.state.{Store, Heap, State, StateFormatter}
import state.terms._

class DefaultStateFormatter[ST <: Store[ST], H <: Heap[H], S <: State[ST, H, S]]
    extends StateFormatter[ST, H, S, String] {
			
  def format(σ: S) = {
		val γ = format(σ.γ)			
		val h = format(σ.h, "h")
		val g = format(σ.g, "g")
		val π = format(σ.π)
		
		"σ(\n  %s, \n  %s, \n  %s, \n  %s\n)".format(γ, h, g, π)
	}
	
	def format(γ: ST) = {
		val map = γ.values
		if (map.isEmpty) "Ø" else "γ" + map.mkString("(", ", ", ")")
	}
	
	def format(h: H) = format(h, "h")
	
	private def format(h: H, id: String) = {
		val values = h.values
		if (values.isEmpty) "Ø" else id + values.mkString("(", ", ", ")")
	}
	
	def format(π: Set[Term]) = {
		/* Attention: Hides non-null and combine terms. */
		if (π.isEmpty) "Ø"
		else
			"π" + (π.filterNot {
				case c: Eq if    c.p0.isInstanceOf[Combine]
														|| c.p1.isInstanceOf[Combine] => true
				case Not(TermEq(_, Null())) => true
				case _ => false
			}).mkString("(", ", ", ")")	
	}
}
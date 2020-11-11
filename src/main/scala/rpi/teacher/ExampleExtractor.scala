package rpi.teacher

import rpi._
import rpi.util.{Collections, UnionFind}
import viper.silicon.interfaces.SiliconRawCounterexample
import viper.silicon.state.{BasicChunk, Heap, Store, terms}
import viper.silicon.state.terms.Term
import viper.silver.{ast => sil}
import viper.silver.verifier.{Model, SingleEntry, VerificationError}
import viper.silver.verifier.reasons.InsufficientPermission

/**
  * Extracts examples from verification errors.
  *
  * @param teacher The pointer to the teacher.
  */
class ExampleExtractor(teacher: Teacher) {
  /**
    * Returns the pointer to the inference.
    *
    * @return The inference.
    */
  def inference: Inference = teacher.inference

  /**
    * Extracts examples from the given verification error.
    *
    * @param error  The verification error.
    * @param triple The offending triple.
    * @return The extracted examples.
    */
  def extractExample(error: VerificationError, triple: Triple): Example = {
    if (Config.debugPrintError) {
      println("----- error -----")
      println(error)
    }

    // extract states and model
    val counter = error.counterexample match {
      case Some(value: SiliconRawCounterexample) => value
    }
    val (initial, current) = extractStates(counter)
    val model = counter.model

    // extract location that caused the permission failure
    val offendingLocation = error.reason match {
      case InsufficientPermission(offending) => offending
    }

    // adapt the location to to its context if it comes from within a specification predicate
    val currentLocation =
      if (current.label == Labels.POST_STATE) {
        // get specification predicate and specification
        val predicate = getPredicate(triple.posts)
        val specification = inference.specifications(predicate.predicateName)
        // build formal-to-actual map
        val map = {
          val actuals = predicate.args
          val formals = specification.variables
          formals.zip(actuals).toMap
        }
        // adapt location
        offendingLocation.transform {
          case variable: sil.LocalVar => map(variable)
        }
      } else offendingLocation

    // the initial record
    lazy val initialRecord = {
      // get specification predicate and specification
      val predicate = getPredicate(triple.pres)
      val specification = inference.specifications(predicate.predicateName)
      // compute predicate abstraction of state
      val atoms = specification.atoms
      // TODO: Map abstraction of post-state and combine.
      val abstraction = abstractState(initial, atoms)
      // map current location to initial specification predicate
      val initialLocations: Set[sil.LocationAccess] = {
        // create reachability information
        val reachability = {
          val actuals = predicate.args.map { case sil.LocalVar(name, _) => name }
          val formals = specification.variables
          val map = actuals.zip(formals).toMap
          Reachability(current, initial, map)
        }

        currentLocation match {
          case sil.FieldAccess(receiver, field) =>
            val adaptedSet = reachability.get(receiver)
            adaptedSet.map { adapted => sil.FieldAccess(adapted, field)() }
          case sil.PredicateAccess(arguments, name) =>
            val adaptedSet = Collections.product(arguments.map(reachability.get))
            adaptedSet.map { adapted => sil.PredicateAccess(adapted, name)() }
        }
      }
      // create record
      Record(specification.predicate, abstraction, initialLocations)
    }

    // the current record
    lazy val currentRecord = {
      // get predicate and specifications
      val predicate = getPredicate(triple.posts)
      val specifications = inference.specifications(predicate.predicateName)
      // compute predicate abstraction of state
      val atoms = specifications.atoms
      // TODO: Use this abstraction
      val abstraction = abstractState(current, atoms)
      println(abstraction)
      // create record
      Record(specifications.predicate, abstraction, Set(offendingLocation))
    }

    // create and return example
    if (current.label == Labels.POST_STATE) {
      // evaluate permission amount
      val variable = s"perm_${teacher.encode(currentLocation)}"
      val term = current.store(variable)
      val permission = evaluate(term, model)
      // create implication or negative example depending on permission amount
      if (permission == "0.0") Implication(currentRecord, initialRecord)
      else Negative(currentRecord)
    } else Positive(initialRecord)
  }

  /**
    * Returns the predicate access corresponding to the inferred specifications.
    * TODO: Rework/remove this when reworking the triples.
    *
    * @param specifications The specifications.
    * @return The predicate.
    */
  private def getPredicate(specifications: Seq[sil.Exp]): sil.PredicateAccess =
    specifications
      .collectFirst {
        case sil.PredicateAccessPredicate(location, _) => location
      }
      .get

  /**
    * Returns the abstraction of the given state.
    *
    * NOTE: the atoms are expected to be in the "correct" order.
    *
    * @param state The state.
    * @param atoms The atomic predicates.
    * @return
    */
  private def abstractState(state: State, atoms: Seq[sil.Exp]): AbstractState = {
    val pairs = atoms
      .zipWithIndex
      .map { case (atom, i) =>
        val variable = s"${state.label}_p_$i"
        state.store(variable) match {
          case terms.True() => (atom, true)
          case terms.False() => (atom, false)
        }
      }
    AbstractState(pairs)
  }

  /**
    * Returns a pair of states where the first state is the pre-state and the second state is either the current state
    * or the post-state, depending on whether the execution failed or whether the assertion of the post-condition
    * failed.
    *
    * @param counter The counter-example.
    * @return The initial and the current state.
    */
  private def extractStates(counter: SiliconRawCounterexample): (State, State) = {
    // get path conditions and silicon state
    val conditions = counter.conditions
    val siliconState = counter.state

    // build partitions of equivalent terms
    val partitions = new UnionFind[Term]
    conditions.foreach {
      case terms.BuiltinEquals(left, right) =>
        partitions.union(left, right)
      case _ => // do nothing
    }

    // build store
    val siliconStore = siliconState.g
    val store = buildStore(siliconStore, partitions)

    // build heaps
    val siliconInitial = siliconState.oldHeaps(Labels.PRE_STATE)
    val siliconCurrent = siliconState.oldHeaps.getOrElse(Labels.POST_STATE, siliconState.h)
    val initialHeap = buildHeap(siliconInitial, partitions)
    val currentHeap = buildHeap(siliconCurrent, partitions)

    // build states
    // TODO: Possibly restrict stores?
    val currentLabel = {
      val isPost = siliconState.oldHeaps.isDefinedAt(Labels.POST_STATE)
      if (isPost) Labels.POST_STATE else Labels.CURRENT_STATE
    }
    val initial = State(Labels.PRE_STATE, store, initialHeap)
    val current = State(currentLabel, store, currentHeap)

    // return states
    (initial, current)
  }

  /**
    * Builds a store map from a Silicon store.
    *
    * @param store      The store.
    * @param partitions The partitions of equivalent terms.
    * @return The store map.
    */
  private def buildStore(store: Store, partitions: UnionFind[Term]): Map[String, Term] =
    store
      .values
      .map { case (variable, term) =>
        val value = partitions.find(term)
        variable.name -> value
      }

  /**
    * Builds a heap map from a Silicon heap.
    *
    * @param heap       The heap.
    * @param partitions The partitions of equivalent terms.
    * @return The heap map.
    */
  private def buildHeap(heap: Heap, partitions: UnionFind[Term]): Map[Term, Map[String, Term]] = {
    val empty = Map.empty[Term, Map[String, Term]]
    heap
      .values
      .foldLeft(empty) {
        case (result, chunk: BasicChunk) =>
          // extract information from heap chunk
          val receiver = partitions.find(chunk.args.head)
          val field = chunk.id.name
          val value = partitions.find(chunk.snap)
          // update heap map
          val fields = result
            .getOrElse(receiver, Map.empty)
            .updated(field, value)
          result.updated(receiver, fields)
      }
  }

  /**
    * A state extracted from the Silicon verifier.
    *
    * @param label The label allowing to identify a specific state.
    * @param store The store.
    * @param heap  The heap.
    */
  private case class State(label: String, store: Map[String, Term], heap: Map[Term, Map[String, Term]]) {
    /**
      * Evaluates the given Silver expression into a Silicon term.
      *
      * @param expression The expression to evaluate.
      * @return The resulting term.
      */
    def evaluate(expression: sil.Exp): Term = expression match {
      case sil.LocalVar(name, _) => store(name)
      case sil.FieldAccess(receiver, field) => heap(evaluate(receiver))(field.name)
    }
  }

  /**
    * A helper class used to compute a set of expressions that reach the same values in a target state as a given
    * expression in the source state.
    *
    * @param source The source state.
    * @param target The target state.
    * @param map    The actual-to-formal map  // TODO: Move somewhere else?
    */
  private case class Reachability(source: State, target: State, map: Map[String, sil.LocalVar]) {
    /**
      * The reachability map.
      */
    private lazy val reachability = recurse(initial, steps = 3)

    def get(expression: sil.Exp): Set[sil.Exp] = {
      val term = source.evaluate(expression)
      val paths = reachability(term)
      paths.map { path =>
        path.transform { case sil.LocalVar(name, _) => map(name) }
      }
    }

    /**
      * Returns the reachability map of everything that is directly reachable from the store of the state.
      *
      * @return The initial reachability map.
      */
    private def initial: Map[Term, Set[sil.Exp]] = {
      val empty = Map.empty[Term, Set[sil.Exp]]
      target
        .store
        .filterKeys(_.endsWith("_init"))
        .foldLeft(empty) {
          case (result, (initial, term)) =>
            val variable = sil.LocalVar(initial.dropRight(5), sil.Ref)()
            val variables = result.getOrElse(term, Set.empty) + variable
            result.updated(term, variables)
        }
    }

    /**
      * Updates the current reachability map by recursing the given number of steps.
      *
      * @param current The current reachability map.
      * @param steps   The number of steps.
      * @return The reachability map.
      */
    private def recurse(current: Map[Term, Set[sil.Exp]], steps: Int): Map[Term, Set[sil.Exp]] =
      if (steps == 0) current
      else {
        // compute next step of the reachability map
        val empty = Map.empty[Term, Set[sil.Exp]]
        val next = current.foldLeft(empty) {
          case (map1, (term, paths)) =>
            target.heap.getOrElse(term, Map.empty).foldLeft(map1) {
              case (map2, (name, value)) =>
                val field = sil.Field(name, sil.Ref)()
                val extendedPaths = paths.map { path => sil.FieldAccess(path, field)() }
                val updatedPaths = map2.getOrElse(value, Set.empty) ++ extendedPaths
                map2.updated(value, updatedPaths)
            }
        }
        // recurse and combine results
        Collections.combine[Term, Set[sil.Exp]](current, recurse(next, steps - 1), _ ++ _)
      }
  }

  /**
    * Evaluates the given term in the given model.
    *
    * @param term  The term to evaluate.
    * @param model The model.
    * @return The value of the term in the given model.
    */
  private def evaluate(term: Term, model: Model): String = term match {
    // variable
    case terms.Var(id, _) => model.entries.get(id.name) match {
      case Some(SingleEntry(value)) => value
      case _ => ???
    }
    // ???
    case terms.SortWrapper(wrapped, _) => evaluate(wrapped, model)
    case terms.First(arg) => s"fst(${evaluate(arg, model)})"
    case terms.Second(arg) => s"snd(${evaluate(arg, model)}"
    // permissions
    case terms.FullPerm() => "1.0"
    case terms.NoPerm() => "0.0"
    case terms.PermPlus(left, right) =>
      val leftValue = evaluate(left, model).toDouble
      val rightValue = evaluate(right, model).toDouble
      String.valueOf(leftValue + rightValue)
    // boolean terms
    case terms.BuiltinEquals(left, right) =>
      val leftValue = evaluate(left, model)
      val rightValue = evaluate(right, model)
      String.valueOf(leftValue == rightValue)
    case terms.Ite(cond, left, right) =>
      evaluate(cond, model) match {
        case "true" => evaluate(left, model)
        case "false" => evaluate(right, model)
      }
    case _ => ???
  }

}

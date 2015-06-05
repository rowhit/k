package k.frontend

import java.util.HashMap
import com.microsoft.z3._
import com.microsoft.z3.{ Symbol => Z3Symbol }
import collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.{ HashMap => MMap }

object Util {
  def ??? : Nothing = null.asInstanceOf[Nothing]
}
import Util._

class DataTypes(ctx: Context) {
  type TypeName = String
  type FieldName = String

  private var datatypes: Map[Type, DataType] = Map()

  def addDataType(ty: Type, datatype: DataType) {
    datatypes += (ty -> datatype)
  }

  def addDataType(name: String, argTypes: List[Type], fields: List[(String, Type)]) {
    val fieldNames: Array[FieldName] = fields.toArray map { case (n, _) => n }
    val fieldSorts: Array[Sort] = fields.toArray map { case (_, t) => getDataType(t).sort }
    val mkDatatype: Constructor = ctx.mkConstructor(s"mk$name", s"is$name", fieldNames, fieldSorts, null)
    val datatypeSort: DatatypeSort = ctx.mkDatatypeSort(name, Array(mkDatatype))
    val constructor: FuncDecl = datatypeSort.getConstructors.apply(0)
    val selectors: Array[FuncDecl] = datatypeSort.getAccessors.apply(0)
    val fieldDecls: Map[FieldName, FuncDecl] = (fieldNames zip selectors).toMap
    val dataType = DataType(datatypeSort, constructor, fieldDecls)
    addDataType(IdentType(QualifiedName(List(name)), argTypes), dataType)
  }

  //  Array < String > argnames = new Array < String > ("first", "second");
  //  Array < z3.Sort > argsorts = new Array < z3.Sort > (ctx.getIntSort(), ctx.getIntSort());
  //  z3.Constructor mkpair = ctx.mkConstructor("mkpair", "ispair", argnames, argsorts, null);
  //  z3.DatatypeSort pair = ctx.mkDatatypeSort("pair", new Array < z3.Constructor > (mkpair));

  def addTupleType(fieldTypes: List[Type]) {
    val tupleSize: Int = fieldTypes.length
    val constructorSymbol: StringSymbol = ctx.mkSymbol(s"mkTuple")
    val fieldNames: Array[FieldName] = (for (i <- 1 to tupleSize) yield s"sel_$i").toArray
    val fieldSymbols: Array[Z3Symbol] = fieldNames map (ctx.mkSymbol(_))
    val fieldSorts: Array[Sort] = fieldTypes.toArray map (getDataType(_).sort)
    val tupleSort: TupleSort = ctx.mkTupleSort(constructorSymbol, fieldSymbols, fieldSorts)
    val tupleConstructor: FuncDecl = tupleSort.mkDecl()
    val fieldDecls: Map[FieldName, FuncDecl] = (fieldNames zip tupleSort.getFieldDecls).toMap
    val dataType = DataType(tupleSort, tupleConstructor, fieldDecls)
    addDataType(CartesianType(fieldTypes), dataType)
  }

  def getDataType(ty: Type): DataType = datatypes(ty)

  def getSort(ty: Type): Sort = getDataType(ty).sort

  addDataType(RealType, DataType(ctx.getRealSort(), null, null))
  addDataType(BoolType, DataType(ctx.getBoolSort(), null, null))
  addDataType(RealType, DataType(ctx.getRealSort(), null, null))
}

case class DataType(sort: Sort, constructor: FuncDecl, selectors: Map[String, FuncDecl])

object K2Z3 {

  val debug: Boolean = false
  var cfg: Map[String, String] = Map("model" -> "true",
    "auto-config" -> "true")
  var ctx: Context = new Context(cfg)
  var idents: MMap[String, (Expr, com.microsoft.z3.StringSymbol)] = MMap()
  var model: com.microsoft.z3.Model = null
  val tc: TypeChecker = new TypeChecker(null)
  var datatypes: DataTypes = null

  def error(msg:String) = Misc.error("K2Z3", msg)
  
  def declareDatatypes(ctx: Context) {
    datatypes = new DataTypes(ctx)
    datatypes.addTupleType(List(RealType, BoolType))
    datatypes.addDataType("A", Nil, List("x" -> RealType, "y" -> BoolType))
  }

  def parseTest() {
    reset()
    val result = ctx.parseSMTLIB2String(
      """
        (set-option :smt.mbqi true)(declare-fun f (Int) Int)(declare-fun p (Int) Bool)(declare-fun p2 (Int) Bool)(declare-const a Int)(declare-const b Int)(declare-const c Int)(assert (forall ((x Int))                 (=> (not (p x)) (= (f x) (+ x 1)))))(assert (forall ((x Int))                 (=> (and (p x) (not (p2 x))) (= (f x) x))))(assert (forall ((x Int))                 (=> (p2 x) (= (f x) (- x 1)))))(assert (p b))(assert (p c))(assert (p2 a))(assert (> (f a) b))(check-sat)
        """, null, null, null, null)
    println("Result is " + result)

    SolveExp(result)
    PrintModel()
  }

  def declareFunctions(ctx: Context) {
    // function f : Int -> Int inside A
    val intType = datatypes.getSort(RealType)
    val theAType = IdentType(QualifiedName(List("A")), Nil)
    val theADatatype: DataType = datatypes.getDataType(theAType)
    val theASort = theADatatype.sort
    val fDecl: FuncDecl = ctx.mkFuncDecl("f", Array(theASort, intType), intType)

  }

  def reset() {
    model = null
    idents = new MMap()
    ctx = new Context(cfg)
    declareDatatypes(ctx)
    declareFunctions(ctx)
  }

  def PrintModel() {

    if (model != null) {
      // New method of printing the model. Here we get all the 
      // constants and the functions, and then print their 
      // interpretations. The older method (commented out underneath)
      // relies on the list of identifiers and symbols that we have 
      // built up during translation of expressions. It does not 
      // get existentially quantified variables at the highest level. 
      // Note that in the new method, the value that is printed out for
      // the decl is an "interpretation".
      println("<<++")
      model.getConstDecls.foreach { x =>
        println(s"\tConst: ${x.getName.toString.split("!")(0)} ${model.getConstInterp(x).toString}")
      }
      model.getFuncDecls.foreach {
        x => println(s"\tFunc: ${x.getName.toString.split("!")(0)}  ${model.getFuncInterp(x)}")
      }
      //      model.getDecls.foreach { x => println(s"\tDecls: ${x.getName}  $x") }
      println("-->>")

      //for ((i, (e, s)) <- idents) {
      //println(e.toString() + " = " + model.evaluate(e, false))
      //}
    }
  }

  def solveSMT(smtModel: String) {
    reset()
    val boolExp = ctx.parseSMTLIB2String(smtModel, null, null, null, null)
    model = SolveExp(boolExp)
    PrintModel()
  }
  
  def SolveExp(e: Exp): com.microsoft.z3.Model = {
    reset()
    val boolExpr = Expr2Z3(e).asInstanceOf[BoolExpr];
    SolveExp(boolExpr)
  }

  def SolveExp(e: BoolExpr): com.microsoft.z3.Model = {
    var solver: Solver = ctx.mkSolver()
    solver.add(e)
    val params = ctx.mkParams()
    params.add("algebraic_number_evaluator", true)
    //params.add("pp.decimal", true)
    solver.setParameters(params)
    if (debug) println("solving " + solver)

    val status = solver.check()
    if (Status.SATISFIABLE == status) {
      println("SAT")
      model = solver.getModel
    } else if (status == Status.UNSATISFIABLE) {
      println("UNSAT")
    } else {
      //println("UNKNOWN")
      model = null
    }

    model
  }

  def getZ3Function(exp: Exp) = {
    // TODO
    // have to ensure that all functions have been put into Z3
    // 
    null
  }

  // create a function in Z3 with the given name
  def createFunction(name: String, f: FunDecl) {
    // TODO 
  }

  def Class2Z3(e: EntityDecl): Sort = {
    val (fields, types): (List[String], List[Sort]) =
      e.members.foldLeft((List[String](), List[Sort]()))((r, f) =>
        if (f.isInstanceOf[PropertyDecl])
          //(r._1 ++ List(f.asInstanceOf[PropertyDecl].name), r._2 ++ List(f.asInstanceOf[PropertyDecl].ty.toString))
          (r._1 ++ List(f.asInstanceOf[PropertyDecl].name), r._2 ++ List(ctx.getRealSort))
        else
          r)
    ctx.mkDatatypeSort(e.ident,
      List(ctx.mkConstructor(e.ident + "_cons", "is_" + e.ident + "_cons", fields.toArray, types.toArray, null)).toArray)

  }

  def Expr2Z3(e: Exp): com.microsoft.z3.Expr = {
    //println(s"Called for $e")
    e match {

      //case FunApplExp(exp, args) =>
      //  var function = getZ3Function(exp).asInstanceOf[FuncDecl]
      //  var argsZ3: Array[Expr] = args.map(a => Expr2Z3(a)).toArray
      //  ctx.mkApp(function, argsZ3: _*)
      case ParenExp(e) =>
        Expr2Z3(e)
      case TupleExp(es) =>
        val vs = es map Expr2Z3
        val tupleType = tc.inferTypeFrom("es", CartesianType(List(RealType, BoolType)))
        val mkTuple = datatypes.getDataType(tupleType).constructor
        mkTuple(vs(0), vs(1))
      case IdentExp(i) =>
        //println(s"IdentExp for $i")
        idents.get(i) match {
          case None =>
            var s = ctx.mkSymbol(i)
            var ie = ctx.mkRealConst(s)
            idents.put(i, (ie, s))
            ie
          case Some(x) =>
            x._1
        }
      case DotExp(exp, ident) =>
        exp match {
          case IdentExp(id) => Expr2Z3(IdentExp(id + "." + ident))
          case _ =>
            var obj: Expr = Expr2Z3(exp)
            val theType = tc.inferTypeFrom("exp", IdentType(QualifiedName(List("A")), Nil))
            val datatype: DataType = datatypes.getDataType(theType)
            val selector: FuncDecl = datatype.selectors(ident)
            selector(obj)
        }
      case FunApplExp(exp, args) =>
        var obj: Expr = Expr2Z3(exp)
        val theType = tc.inferTypeFrom("exp", IdentType(QualifiedName(List("A")), Nil))
        val isConstructor = false // TODO: for constructor make this true
        if (isConstructor) {
          // constructor application
          val datatype: DataType = datatypes.getDataType(theType)
          val constructor: FuncDecl = datatype.constructor
          val arguments: List[Expr] = args map (Expr2Z3(_))
          constructor(arguments: _*)
        } else {
          // normal function application

          // 1. create an uninterpreted function

          val functionDecl = ctx.mkFuncDecl(exp.toString, args.map(a => ctx.getRealSort).toArray[Sort], ctx.getRealSort)

          // 2. apply the uninterpreted function
          ctx.mkApp(functionDecl, args.map(Expr2Z3(_)): _*)

        }
      case PositionalArgument(exp) =>
        Expr2Z3(exp)
      case BinExp(e1, o, e2) =>
        o match {
          case LT =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkLt(v1, v2)
          case LTE =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkLe(v1, v2)
          case GT =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkGt(v1, v2)
          case GTE =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkGe(v1, v2)
          case AND =>
            //println(s"Doing and for $e1, $e2")
            var v1: BoolExpr = Expr2Z3(e1).asInstanceOf[BoolExpr]
            var v2: BoolExpr = Expr2Z3(e2).asInstanceOf[BoolExpr]
            ctx.mkAnd(v1, v2)
          case OR =>
            var v1: BoolExpr = Expr2Z3(e1).asInstanceOf[BoolExpr]
            var v2: BoolExpr = Expr2Z3(e2).asInstanceOf[BoolExpr]
            ctx.mkOr(v1, v2)
          case IMPL =>
            //println("impl is " + e)
            var v1: BoolExpr = Expr2Z3(e1).asInstanceOf[BoolExpr]
            var v2: BoolExpr = Expr2Z3(e2).asInstanceOf[BoolExpr]
            ctx.mkImplies(v1, v2)
          case IFF =>
            var v1: BoolExpr = Expr2Z3(e1).asInstanceOf[BoolExpr]
            var v2: BoolExpr = Expr2Z3(e2).asInstanceOf[BoolExpr]
            ctx.mkIff(v1, v2)
          case EQ =>
            var v1: Expr = Expr2Z3(e1).asInstanceOf[Expr]
            var v2: Expr = Expr2Z3(e2).asInstanceOf[Expr]
            ctx.mkEq(v1, v2)
          case NEQ =>
            var v1 = Expr2Z3(e1)
            var v2 = Expr2Z3(e2)
            ctx.mkNot(ctx.mkEq(v1, v2))
          case MUL =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkMul(v1, v2)
          case DIV =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkDiv(v1, v2)
          case REM =>
            var v1: IntExpr = Expr2Z3(e1).asInstanceOf[IntExpr]
            var v2: IntExpr = Expr2Z3(e2).asInstanceOf[IntExpr]
            ctx.mkRem(v1, v2)
          case ADD =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkAdd(v1, v2)
          case SUB =>
            var v1: ArithExpr = Expr2Z3(e1).asInstanceOf[ArithExpr]
            var v2: ArithExpr = Expr2Z3(e2).asInstanceOf[ArithExpr]
            ctx.mkSub(v1, v2)
          case ASSIGN =>
            var v1: Expr = Expr2Z3(e1).asInstanceOf[Expr]
            var v2: Expr = Expr2Z3(e2).asInstanceOf[Expr]
            ctx.mkEq(v1, v2)
          case TUPLEINDEX =>
            var v1: Expr = Expr2Z3(e1).asInstanceOf[Expr]
            var v2: Expr = Expr2Z3(e2).asInstanceOf[Expr]
            val tupleType = tc.inferTypeFrom("e1", CartesianType(List(RealType, BoolType)))
            val datatype = datatypes.getDataType(tupleType)
            if (v2 == ctx.mkReal(1))
              datatype.selectors("sel_1").apply(v1)
            else
              datatype.selectors("sel_2").apply(v1)
        }
      case UnaryExp(o, e) =>
        o match {
          case NOT =>
            var v: BoolExpr = {
              val ev = Expr2Z3(e)
              if (ev.isInstanceOf[RealExpr]) {
                ctx.mkNot(ctx.mkEq(ev, ctx.mkReal(0)))
              } else {
                ev.asInstanceOf[BoolExpr]
              }
            }

            ctx.mkNot(v)
          case NEG =>
            var v: ArithExpr = Expr2Z3(e).asInstanceOf[ArithExpr]
            ctx.mkMul(ctx.mkReal(-1), v)
        }
      case IntegerLiteral(i) =>
        ctx.mkReal(i)
      case BooleanLiteral(b) =>
        ctx.mkBool(b)
      case RealLiteral(r) =>
        ctx.mkReal(r.toString)
      case QuantifiedExp(quantifier, bindings, expression) =>
        var qtypes = new ListBuffer[com.microsoft.z3.Sort]()
        var names = new ListBuffer[com.microsoft.z3.Symbol]()
        var patterns = new ListBuffer[com.microsoft.z3.Pattern]() // not used
        var ies = new ListBuffer[Expr]()
        for (b <- bindings) {
          for (p <- b.patterns) {
            p match {
              case IdentPattern(x) =>
                idents.get(x) match {
                  case None =>
                    val xSym = ctx.mkSymbol(x)
                    var ie = ctx.mkConst(xSym, ctx.getRealSort)
                    idents.put(x, (ie, xSym))
                    names.add(xSym)
                    ies.add(ie)
                    b.collection match {
                      case TypeCollection(ty) =>
                        ty match {
                          case BoolType => qtypes.add(ctx.getBoolSort())
                          case IntType =>
                            qtypes.add(ctx.getIntSort())
                            val pattern = ctx.mkPattern(ie)
                            patterns.add(ctx.mkPattern(ie)) // use pattern, but not used anyway
                          case RealType =>
                            qtypes.add(ctx.getRealSort())
                            val pattern = ctx.mkPattern(ie)
                            patterns.add(ctx.mkPattern(ie)) // use pattern, but not used anyway
                          case _ =>
                            error("Only bool, int, and real primitive types are supported for quantified expressions in Z3." + expression)
                        }
                      case _ =>
                        error("Only type collections are supported for quantified expressions in Z3." +
                          "\nPlease check expression " + e)
                    }
                  case Some(x) => () // so you can't quantify over an existing variable?
                }

              case _ =>
                error("Only literal and ident patterns are supported for quantified expressions in Z3." +
                  "Please check expression " + expression)
            }
          }
        }

        var body: Expr = Expr2Z3(expression)

        // There are two ways to construct quantified expressions in Z3
        // one is by using named constants
        // the other is by de-Brujin indexed variables.
        // We have to be careful, because there are no checks for actually 
        // checking if you are mixing the two and doing it incorrectly
        // The following uses de-Brujin indexed variables for forall
        // and named constants for exists. 
        // This probably can be cleaned up, but it is the only way I got
        // it to work.
        // KH: I think that it does not work as expected for existential quantification.
        // since variables are all de-Brujin variables, and since symbols are used for
        // existential, it does not work. It looks like it works, but I think it is
        // not working the way it is intended to.
        quantifier match {
          case Forall =>
            ctx.mkForall(ies.toArray, body, 0, null,
              null, null, null)
          case Exists =>
            ctx.mkExists(ies.toArray, body, 0, null,
              null, null, null)
          //            ctx.mkExists(qtypes.toArray, names.toArray,
          //              body, 1, null, null, null, null)
          //ctx.mkExists(ies.toArray, body, 0, null, null, null, null)
        }
    }
  }

}

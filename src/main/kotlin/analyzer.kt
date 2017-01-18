import com.github.javaparser.ASTParserConstants
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.observer.ObservableProperty
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import java.io.File

val PATH_TO_JAVAPARSER_SRC = File("../javaparser/javaparser-core/src/main/java/")

fun Statement.isCallTo(methodName: String, nargs : Int = -1) = (this is ExpressionStmt) && this.expression.isCallTo(methodName, nargs)
fun Statement.getArg(index: Int) = (this as ExpressionStmt).expression.getArg(index)

fun Expression.isCallTo(methodName: String, nargs : Int = -1) : Boolean {
    if (this is MethodCallExpr && this.name.id.equals(methodName)) {
        if (nargs == -1 || nargs == this.arguments.size) {
            return true
        }
    }
    return false
}

fun Expression.getArg(index: Int) = (this as MethodCallExpr).arguments[index]

interface CstNode {
    fun javaStatements() : List<JavaStatement>
    fun transform() : CstNode
}

data class CstSequence(val elements: List<CstNode>) : CstNode {
    override fun javaStatements(): List<JavaStatement> = elements.fold(emptyList<JavaStatement>(), {l, el -> l + el.javaStatements()})
    override fun transform(): CstNode = CstSequence(elements.map { it.transform() })
    fun append(node: CstNode) = CstSequence(elements + listOf(node))
}

fun stringToTokens(text: String, tokens: List<CstToken> = emptyList()) : CstSequence? {
    if (text.startsWith(" ")) {
        return stringToTokens(text.substring(1), tokens + listOf(CstToken(0, " ")))
    }
//    if (text.startsWith("'")) {
//        return stringToTokens(text.substring(1), tokens + listOf(CstToken(ASTParserConstants., "'")))
//    }
    if (text.isEmpty()) {
        return CstSequence(tokens)
    }
    var longestFound : CstToken? = null
    for (i in ASTParserConstants.tokenImage.indices) {
        val tokenImage = ASTParserConstants.tokenImage[i]
        if (tokenImage.startsWith("\"") && tokenImage.endsWith("\"")) {
            val tokenImageContent = tokenImage.substring(1, tokenImage.length - 1)
            if (text.startsWith(tokenImageContent)) {
                if (longestFound == null || text.length > longestFound.text.length) {
                    longestFound = CstToken(i, tokenImageContent)
                }
            }
        }
    }
    if (longestFound != null) {
        return stringToTokens(text.substring(longestFound.text.length), tokens + listOf(longestFound))
    }
    return null
}

data class JavaStatement(val wrapped: Statement) : CstNode {
    override fun javaStatements(): List<JavaStatement> = listOf(this)
    override fun transform(): CstNode {
        if (this.wrapped.isCallTo("printJavaComment")) {
            return CstComment
        }
        if (this.wrapped.isCallTo("println", nargs=0)) {
            return CstNewline
        }
        if (this.wrapped.isCallTo("print", nargs=1) && this.wrapped.getArg(0) is StringLiteralExpr) {
            return stringToTokens((this.wrapped.getArg(0) as StringLiteralExpr).value) ?: this
        }
        if (this.wrapped.isCallTo("println", nargs=1) && this.wrapped.getArg(0) is StringLiteralExpr) {
            val r = stringToTokens((this.wrapped.getArg(0) as StringLiteralExpr).value)
            return if (r != null) {
                r.append(CstNewline)
            } else {
                this
            }
        }
        if (this.wrapped.isCallTo("printAnnotations")) {
            return CstProperty(ObservableProperty.ANNOTATIONS)
        }
        if (this.wrapped.isCallTo("indent")) {
            return CstIndent
        }
        if (this.wrapped.isCallTo("unindent")) {
            return CstUnindent
        }
        return this
    }
}

interface TransformedCstNode : CstNode {
    override fun javaStatements(): List<JavaStatement> = emptyList()
    override fun transform(): CstNode = this
}

data class CstProperty(val property: ObservableProperty) : TransformedCstNode

object CstComment : TransformedCstNode
object CstNewline : TransformedCstNode
object CstIndent : TransformedCstNode
object CstUnindent : TransformedCstNode
data class CstToken(val tokenCode: Int, val text: String) : TransformedCstNode

fun processPrintString(text: String) {
    if (text.startsWith(" ")) {
        println("    space")
        processPrintString(text.substring(1))
        return
    }
    if (text.endsWith(" ")) {
        processPrintString(text.substring(0, text.length - 1))
        println("    space")
        return
    }
    println("    token '$text'")
}

fun processStatements(statements: NodeList<Statement>, index: Int = 0) : Boolean {
    if (statements.size == index) {
        return true
    }
    val s = statements[index]
    if (s is ExpressionStmt && s.expression is MethodCallExpr) {
        val mce = s.expression as MethodCallExpr
        if (mce.name.id.equals("println")) {
            println("    newline")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("print")) {
            if (mce.arguments[0] is StringLiteralExpr) {
                processPrintString((mce.arguments[0] as StringLiteralExpr).value)
                return processStatements(statements, index + 1)
            }
            if (mce.arguments[0] is MethodCallExpr
                    && ((mce.arguments[0]) as MethodCallExpr).scope.get() is NameExpr
                    && (((mce.arguments[0]) as MethodCallExpr).scope.get() as NameExpr).name.id.equals("n")) {
                println("    property ${(mce.arguments[0] as MethodCallExpr).name.id}")
                return processStatements(statements, index + 1)
            }
        }
        if (mce.name.id.equals("printJavaComment")) {
            println("    comment")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("printAnnotations")) {
            println("    annotations")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("printMemberAnnotations")) {
            println("    annotations")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("printModifiers")) {
            println("    modifiers")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("indent")) {
            println("    indent")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("unindent")) {
            println("    unindent")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("printOrphanCommentsBeforeThisChildNode")) {
            println("    printOrphanCommentsBeforeThisChildNode")
            return processStatements(statements, index + 1)
        }
        if (mce.name.id.equals("printOrphanCommentsEnding")) {
            println("    printOrphanCommentsEnding")
            return processStatements(statements, index + 1)
        }
        // optional property
        if (mce.name.id.equals("accept") && mce.scope.get() is MethodCallExpr
                && (mce.scope.get() as MethodCallExpr).name.id.equals("get")
                && (mce.scope.get() as MethodCallExpr).scope.get() is MethodCallExpr) {
            println("    property ${((mce.scope.get() as MethodCallExpr).scope.get() as MethodCallExpr).name.id}")
            return processStatements(statements, index + 1)
        }
        // not optional property
        if (mce.name.id.equals("accept") && mce.scope.get() is MethodCallExpr) {
            println("    property ${(mce.scope.get() as MethodCallExpr).name.id}")
            return processStatements(statements, index + 1)
        }
    }
    if (s is IfStmt && s.condition is MethodCallExpr && (s.condition as MethodCallExpr).name.id.equals("isPresent")) {
        val fieldName = ((s.condition as MethodCallExpr).scope.get() as MethodCallExpr).name
        println("    start optional on $fieldName")
        if (s.thenStmt is BlockStmt) {
            processStatements((s.thenStmt as BlockStmt).statements)
        } else if (s.thenStmt is Statement) {
            val nl = NodeList<Statement>()
            nl.add(s.thenStmt)
            processStatements(nl)
        } else {
            throw UnsupportedOperationException(s.thenStmt.javaClass.canonicalName)
        }
        if (s.elseStmt.isPresent) {
            println("    else optional on $fieldName")
            processStatements((s.elseStmt.get() as BlockStmt).statements)
        }
        println("    end optional on $fieldName")
        return processStatements(statements, index + 1)
    }
    if (s is IfStmt && s.condition is BinaryExpr &&
            (s.condition as BinaryExpr).operator == BinaryExpr.Operator.NOT_EQUALS
            && (s.condition as BinaryExpr).right is NullLiteralExpr) {
        val fieldName = ((s.condition as BinaryExpr).left as MethodCallExpr).name.id
        println("    start optional on $fieldName")
        if (s.thenStmt is BlockStmt) {
            processStatements((s.thenStmt as BlockStmt).statements)
        } else if (s.thenStmt is Statement) {
            val nl = NodeList<Statement>()
            nl.add(s.thenStmt)
            processStatements(nl)
        } else {
            throw UnsupportedOperationException(s.thenStmt.javaClass.canonicalName)
        }
        if (s.elseStmt.isPresent) {
            println("    else optional on $fieldName")
            processStatements((s.elseStmt.get() as BlockStmt).statements)
        }
        println("    end optional on $fieldName")
        return processStatements(statements, index + 1)
    }
    if (s is IfStmt && s.condition is UnaryExpr &&
            (s.condition as UnaryExpr).operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && (s.condition as UnaryExpr).expression is MethodCallExpr
            && ((s.condition as UnaryExpr).expression as MethodCallExpr).name.id.equals("isEmpty")) {
        val fieldName = (((s.condition as UnaryExpr).expression as MethodCallExpr).scope.get() as MethodCallExpr).name.id
        println("    start notEmpty on $fieldName")
        if (s.thenStmt is BlockStmt) {
            processStatements((s.thenStmt as BlockStmt).statements)
        } else if (s.thenStmt is Statement) {
            val nl = NodeList<Statement>()
            nl.add(s.thenStmt)
            processStatements(nl)
        } else {
            throw UnsupportedOperationException(s.thenStmt.javaClass.canonicalName)
        }
        if (s.elseStmt.isPresent) {
            println("    else notEmpty on $fieldName")
            processStatements((s.elseStmt.get() as BlockStmt).statements)
        }
        println("    end notEmpty on $fieldName")
        return processStatements(statements, index + 1)
    }
    println("BAD $s")
    return false
    //throw UnsupportedOperationException(s.toString())
}

var totalOriginal = 0
var totalUntransformed = 0

fun extractConcreteSyntaxModel(visitMethod: MethodDeclaration) : CstNode {
    println("Model for ${visitMethod.parameters[0].type}")
    val initialNode = CstSequence(visitMethod.body.get().statements.map(::JavaStatement))
    val transformedNode = initialNode.transform()
    val n = transformedNode.javaStatements().size
    totalOriginal += initialNode.javaStatements().size
    totalUntransformed += n
    println("  untransformed: $n")
    var i = 1
    transformedNode.javaStatements().forEach { println("    (${i++}/$n) $it") }
    println()
    return initialNode
}

fun main(args: Array<String>) {
    val prettyPrintVisitorFile = File(PATH_TO_JAVAPARSER_SRC.path + File.separator + "com/github/javaparser/printer/PrettyPrintVisitor.java")
    val prettyPrintVisitor = JavaParser.parse(prettyPrintVisitorFile)
    prettyPrintVisitor.getClassByName("PrettyPrintVisitor").get().methods
            .filter { it.name.id.equals("visit") }.forEach { extractConcreteSyntaxModel(it) }

    println()
    println(" totalUntransformed = $totalUntransformed")
    println(" totalOriginal = $totalOriginal")
}
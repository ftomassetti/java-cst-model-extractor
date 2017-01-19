import com.github.javaparser.ASTParserConstants
import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.observer.ObservableProperty
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.utils.Utils
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

fun Expression.isNegated() : Boolean = this is UnaryExpr && this.operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT

fun Expression.getArg(index: Int) = (this as MethodCallExpr).arguments[index]

fun Expression.isIsPresent() = isCallTo("isPresent")

fun Expression.isChildAccessor() = this is MethodCallExpr && (this.name.id.startsWith("get") || this.name.id.startsWith("is"))

private fun capitalize(original: String): String {
    if (original.length < 1) {
        throw IllegalArgumentException("This string is empty")
    } else if (original.length == 1) {
        return original.toUpperCase()
    } else {
        return original.substring(0, 1).toUpperCase() + original.substring(1)
    }
}

fun getterNameToProperty(getterName: String) : ObservableProperty? {
    if (getterName == "getSuperTypes") return ObservableProperty.SUPER
    if (getterName == "getThrownExceptions") return ObservableProperty.THROWN_TYPES
    if (getterName == "isInterface") return ObservableProperty.IS_INTERFACE
    if (getterName == "getMaximumCommonType") return null
    if (getterName == "isUsingDiamondOperator") return null
    if (getterName == "isGeneric") return null
    if (getterName == "isPrefix") return null
    if (getterName == "isDefault") return null
    if (getterName == "isPostfix") return null
    if (getterName == "isThis") return null
    if (getterName == "isStatic") return null
    if (getterName == "isAsterisk") return null
    //println(getterName)
    return ObservableProperty.values().first { getterName == "get" + capitalize(Utils.toCamelCase(it.name))
    || getterName == "is" + capitalize(Utils.toCamelCase(it.name))}
}

interface CstNode {
    fun javaStatements() : List<JavaStatement>
    fun transform() : CstNode
}

data class CstSequence(val elements: List<CstNode>) : CstNode {
    override fun javaStatements(): List<JavaStatement> = elements.fold(emptyList<JavaStatement>(), {l, el -> l + el.javaStatements()})
    override fun transform(): CstNode = CstSequence(elements.map { it.transform() })
    fun append(node: CstNode) = CstSequence(elements + listOf(node))
}

enum class ConditionType {
    IS_PRESENT,
    IS_NOT_EMPTY,
    ATTRIBUTE_VALUE
}

data class CstConditional(val property: ObservableProperty, val condition : ConditionType, val thenCstNode : CstNode, val elseCstNode: CstNode?) : CstNode  {
    override fun javaStatements(): List<JavaStatement> {
        return thenCstNode.javaStatements() + if (elseCstNode == null) emptyList() else elseCstNode.javaStatements()
    }

    override fun transform(): CstNode {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
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
        if (this.wrapped.isCallTo("print", nargs=1) && this.wrapped.getArg(0).isCallTo("getIdentifier")) {
            return CstAttribute(ASTParserConstants.IDENTIFIER, ObservableProperty.IDENTIFIER)
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
        if (this.wrapped.isCallTo("printMemberAnnotations")) {
            return CstProperty(ObservableProperty.ANNOTATIONS)
        }
        if (this.wrapped.isCallTo("printModifiers")) {
            return CstProperty(ObservableProperty.MODIFIERS)
        }
        if (this.wrapped.isCallTo("printTypeArgs")) {
            return CstProperty(ObservableProperty.TYPE_ARGUMENTS)
        }
        if (this.wrapped.isCallTo("printModifiers")) {
            return CstProperty(ObservableProperty.MODIFIERS)
        }
        if (this.wrapped.isCallTo("printArguments")) {
            return CstProperty(ObservableProperty.ARGUMENTS)
        }
        if (this.wrapped.isCallTo("printMembers")) {
            return CstProperty(ObservableProperty.MEMBERS)
        }
        if (this.wrapped.isCallTo("indent")) {
            return CstIndent
        }
        if (this.wrapped.isCallTo("unindent")) {
            return CstUnindent
        }
        if (this.wrapped is IfStmt && this.wrapped.condition.isNegated()) {
            val internalCondition = (this.wrapped.condition as UnaryExpr).expression
            if (internalCondition.isCallTo("isEmpty")) {
                val scope = (internalCondition as MethodCallExpr).scope.get()
                if (scope.isChildAccessor()) {
                    val property = getterNameToProperty((scope as MethodCallExpr).nameAsString)
                    if (property != null) {
                        return CstConditional(property, ConditionType.IS_NOT_EMPTY, JavaStatement(this.wrapped.thenStmt).transform(), this.wrapped.elseStmt.map { JavaStatement(it).transform() }.orElse(null))
                    }
                }
            }
            if (internalCondition.isCallTo("isNullOrEmpty")) {
                val arg = internalCondition.getArg(0)
                if (arg.isChildAccessor()) {
                    val property = getterNameToProperty((arg as MethodCallExpr).nameAsString)
                    if (property != null) {
                        return CstConditional(property, ConditionType.IS_NOT_EMPTY, JavaStatement(this.wrapped.thenStmt).transform(), this.wrapped.elseStmt.map { JavaStatement(it).transform() }.orElse(null))
                    }
                }
            }
        }
        if (this.wrapped is IfStmt && this.wrapped.condition.isCallTo("isPresent")) {
            val scope = (this.wrapped.condition as MethodCallExpr).scope.get()
            if (scope.isChildAccessor()) {
                val property = getterNameToProperty((scope as MethodCallExpr).nameAsString)
                if (property != null) {
                    return CstConditional(property, ConditionType.IS_PRESENT, JavaStatement(this.wrapped.thenStmt).transform(), this.wrapped.elseStmt.map { JavaStatement(it).transform() }.orElse(null))
                }
            }
        }
        if (this.wrapped is IfStmt && this.wrapped.condition.isChildAccessor()) {
            val property = getterNameToProperty((this.wrapped.condition as MethodCallExpr).nameAsString)
            if (property != null) {
                return CstConditional(property, ConditionType.ATTRIBUTE_VALUE, JavaStatement(this.wrapped.thenStmt).transform(), this.wrapped.elseStmt.map { JavaStatement(it).transform() }.orElse(null))
            }
        }
        if (this.wrapped.isCallTo("accept")) {
            val scopeOfAccept = ((this.wrapped as ExpressionStmt).expression as MethodCallExpr).scope.get()
            val getterCall = if (scopeOfAccept.isCallTo("get")) (scopeOfAccept as MethodCallExpr).scope.get() else scopeOfAccept
            if (getterCall.isChildAccessor()) {
                val property = getterNameToProperty((getterCall as MethodCallExpr).nameAsString)
                if (property != null) {
                    return CstProperty(property)
                }
            }
        }
        if (this.wrapped is BlockStmt) {
            return CstSequence(this.wrapped.statements.map { JavaStatement(it).transform() })
        }
        if (this.wrapped.isCallTo("printOrphanCommentsEnding")) {
            return CstOrhpanCommentsEnding
        }
        if (this.wrapped.isCallTo("printOrphanCommentsBeforeThisChildNode")) {
            return CstOrhpanCommentsPreceeding
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
object CstOrhpanCommentsEnding : TransformedCstNode
object CstOrhpanCommentsPreceeding : TransformedCstNode
object CstNewline : TransformedCstNode
object CstIndent : TransformedCstNode
object CstUnindent : TransformedCstNode
data class CstToken(val tokenCode: Int, val text: String) : TransformedCstNode
data class CstAttribute(val tokenCode: Int, val property: ObservableProperty) : TransformedCstNode

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
    println(" totalTransformed = ${totalOriginal - totalUntransformed}")
    println(" totalOriginal = $totalOriginal")
}
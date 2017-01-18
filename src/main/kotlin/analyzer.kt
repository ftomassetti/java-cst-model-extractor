import com.github.javaparser.JavaParser
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.Statement
import java.io.File

val PATH_TO_JAVAPARSER_SRC = File("../javaparser/javaparser-core/src/main/java/")

var bads = 0
var totals = 0

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
        }
        if (mce.name.id.equals("printJavaComment")) {
            println("    comment")
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
    if (s is IfStmt && s.condition is UnaryExpr &&
            (s.condition as UnaryExpr).operator == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && (s.condition as UnaryExpr).expression is MethodCallExpr && ((s.condition as UnaryExpr).expression as MethodCallExpr).name.id.equals("isEmpty")) {
        val fieldName = (((s.condition as UnaryExpr).expression as MethodCallExpr).scope.get() as MethodCallExpr).name.id
        println("    start notEmpty on $fieldName")
        if (s.thenStmt is BlockStmt) {
            processStatements((s.thenStmt as BlockStmt).statements)
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
    bads++
    return false
    //throw UnsupportedOperationException(s.toString())
}

fun extractConcreteSyntaxModel(visitMethod: MethodDeclaration) {
    totals++
    println("Model for ${visitMethod.parameters[0].type}")
    processStatements(visitMethod.body.get().statements)
    println()
}

fun main(args: Array<String>) {
    val prettyPrintVisitorFile = File(PATH_TO_JAVAPARSER_SRC.path + File.separator + "com/github/javaparser/printer/PrettyPrintVisitor.java")
    val prettyPrintVisitor = JavaParser.parse(prettyPrintVisitorFile)
    prettyPrintVisitor.getClassByName("PrettyPrintVisitor").get().methods
            .filter { it.name.id.equals("visit") }.forEach(::extractConcreteSyntaxModel)
    println("bads $bads out of $totals")
}
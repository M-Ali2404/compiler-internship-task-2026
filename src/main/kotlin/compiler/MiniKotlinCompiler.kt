package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser

public class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {

    private var next_arg_id = 0
    // helper to get a fresh variable name
    private fun get_next_arg_name(): String {
        val name = "arg" + next_arg_id
        next_arg_id = next_arg_id + 1
        return name
    }
    public fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        // grab all the functions and put them together
        var all_functions = ""
        val function_list = program.functionDeclaration()

        for (func in function_list) {
            val func_code = visit(func)
            all_functions = all_functions + func_code + "\n\n"
        }

        // build the final java class
        val final_code = "public class " + className + " {\n" + all_functions + "\n}"
        return final_code
    }
    override fun visitFunctionDeclaration(ctx: MiniKotlinParser.FunctionDeclarationContext): String {
        // get the name of the function
        val func_name = ctx.IDENTIFIER().text
        val ret_type = map_type(ctx.type())

        // get the parameters if they exist
        var params_string = ""
        val param_list = ctx.parameterList()

        if (param_list != null) {
            val parameters = param_list.parameter()
            val param_strings = java.util.ArrayList<String>()

            for (p in parameters) {
                val p_type = map_type(p.type())
                val p_name = p.IDENTIFIER().text
                param_strings.add(p_type + " " + p_name)
            }

            // join the parameters together with commas
            var joined = ""
            for (i in 0 until param_strings.size) {
                joined = joined + param_strings.get(i)
                if (i < param_strings.size - 1) {
                    joined = joined + ", "
                }
            }
            params_string = joined
        }

        // check if it is the main function
        if (func_name.equals("main")) {
            val block_statements = ctx.block().statement()
            val inner_code = process_statements(block_statements, null, "Void")

            return "    public static void main(String[] args) {\n        " + inner_code + "\n    }"
        }

        // setup the continuation parameter for normal functions
        var cps_params = ""
        if (params_string.isEmpty()) {
            cps_params = "Continuation<" + ret_type + "> __continuation"
        } else {
            cps_params = params_string + ", Continuation<" + ret_type + "> __continuation"
        }

        val block_statements = ctx.block().statement()
        val inner_code = process_statements(block_statements, "__continuation", ret_type)

        return "    public static void " + func_name + "(" + cps_params + ") {\n        " + inner_code + "\n    }"
    }
    // helper to process a list of statements
    private fun process_statements(
        statements: List<MiniKotlinParser.StatementContext>,
        cont: String?,
        ret_type: String
    ): String {

        // check if we are out of statements
        if (statements.isEmpty()) {
            if (cont != null) {
                if (ret_type.equals("Void")) {
                    return cont + ".accept(null);\nreturn;"
                }
            }
            return ""
        }

        // grab the first statement and the rest of them
        val head = statements.get(0)
        val tail = statements.subList(1, statements.size)

        // check if it is a variable declaration
        val var_decl = head.variableDeclaration()
        if (var_decl != null) {
            return translate_expression(var_decl.expression()) { res ->
                val var_name = var_decl.IDENTIFIER().text
                val var_type = map_type(var_decl.type())

                val current_line = var_type + " " + var_name + " = " + res + ";\n"
                val rest_of_code = process_statements(tail, cont, ret_type)

                return@translate_expression current_line + rest_of_code
            }
        }

        // check if it is an assignment
        val assign = head.variableAssignment()
        if (assign != null) {
            return translate_expression(assign.expression()) { res ->
                val var_name = assign.IDENTIFIER().text

                val current_line = var_name + " = " + res + ";\n"
                val rest_of_code = process_statements(tail, cont, ret_type)

                return@translate_expression current_line + rest_of_code
            }
        }

        // check if it is a return statement
        val ret_stmt = head.returnStatement()
        if (ret_stmt != null) {
            val expr = ret_stmt.expression()

            if (expr == null) {
                if (cont != null) {
                    return cont + ".accept(null);\nreturn;"
                } else {
                    return "return;"
                }
            } else {
                return translate_expression(expr) { res ->
                    if (cont != null) {
                        return@translate_expression cont + ".accept(" + res + ");\nreturn;"
                    } else {
                        return@translate_expression "return;"
                    }
                }
            }
        }

        // check if it is an if statement
        val if_stmt = head.ifStatement()
        if (if_stmt != null) {
            return translate_expression(if_stmt.expression()) { cond ->

                // grab the statements inside the true block and add the tail
                val true_block = if_stmt.block(0).statement()
                val true_combined = java.util.ArrayList(true_block)
                true_combined.addAll(tail)

                val then_code = process_statements(true_combined, cont, ret_type)
                var else_code = ""

                // check if there is an else block
                if (if_stmt.block().size > 1) {
                    val false_block = if_stmt.block(1).statement()
                    val false_combined = java.util.ArrayList(false_block)
                    false_combined.addAll(tail)
                    else_code = process_statements(false_combined, cont, ret_type)
                } else {
                    else_code = process_statements(tail, cont, ret_type)
                }

                return@translate_expression "if (" + cond + ") {\n" + then_code + "\n} else {\n" + else_code + "\n}"
            }
        }

        // check if it is a while loop
        val while_stmt = head.whileStatement()
        if (while_stmt != null) {
            val loop_var = get_next_arg_name()

            // setup the continuation array using our continuation class
            val array_setup = "Continuation<Void>[] " + loop_var + " = new Continuation[1];\n"

            val loop_logic = translate_expression(while_stmt.expression()) { cond ->
                val body_block = while_stmt.block().statement()

                // pass the array element directly as the continuation
                val loop_cont = loop_var + "[0]"

                val body_code = process_statements(body_block, loop_cont, "Void")
                val tail_code = process_statements(tail, cont, ret_type)

                return@translate_expression "if (" + cond + ") {\n" + body_code + "\n} else {\n" + tail_code + "\n}"
            }

            val assign_logic = loop_var + "[0] = (_v) -> {\n" + loop_logic + "\n};\n"
            val start_loop = loop_var + "[0].accept(null);\n"

            return array_setup + assign_logic + start_loop
        }

        // check if it is a standalone expression
        val expr = head.expression()
        if (expr != null) {
            return translate_expression(expr) { res ->
                return@translate_expression process_statements(tail, cont, ret_type)
            }
        }

        // fallback if nothing matches
        return ""
    }
    // helper to translate expressions
    private fun translate_expression(ctx: MiniKotlinParser.ExpressionContext, callback: (String) -> String): String {

        // check if it is a function call
        if (ctx is MiniKotlinParser.FunctionCallExprContext) {
            val func_name = ctx.IDENTIFIER().text

            // get the arguments
            var args: List<MiniKotlinParser.ExpressionContext> = java.util.ArrayList()
            if (ctx.argumentList() != null) {
                args = ctx.argumentList().expression()
            }

            return translate_argument_list(args, 0, java.util.ArrayList()) { arg_values ->

                // join the arguments manually
                var arg_str = ""
                for (i in 0 until arg_values.size) {
                    arg_str = arg_str + arg_values.get(i)
                    if (i < arg_values.size - 1) {
                        arg_str = arg_str + ", "
                    }
                }

                val res_var = get_next_arg_name()
                var target = func_name
                if (func_name.equals("println")) {
                    target = "Prelude.println"
                }

                var sep = ""
                if (!arg_str.isEmpty()) {
                    sep = ", "
                }

                val call_logic = target + "(" + arg_str + sep + "(" + res_var + ") -> {\n" + callback(res_var) + "\n});"
                return@translate_argument_list call_logic
            }
        }
        // check if it is a primary expression literals, variables and more
        if (ctx is MiniKotlinParser.PrimaryExprContext) {
            val primary_val = visit(ctx.primary())
            return callback(primary_val)
        }
        // check if it is a not expression
        if (ctx is MiniKotlinParser.NotExprContext) {
            return translate_expression(ctx.expression()) { v ->
                return@translate_expression callback("(!" + v + ")")
            }
        }

        // check if it is a math or comparison expression binary operators
        if (ctx is MiniKotlinParser.MulDivExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), ctx.getChild(1).text, callback)
        }
        if (ctx is MiniKotlinParser.AddSubExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), ctx.getChild(1).text, callback)
        }
        if (ctx is MiniKotlinParser.ComparisonExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), ctx.getChild(1).text, callback)
        }
        if (ctx is MiniKotlinParser.EqualityExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), ctx.getChild(1).text, callback)
        }
        if (ctx is MiniKotlinParser.AndExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), "&&", callback)
        }
        if (ctx is MiniKotlinParser.OrExprContext) {
            return translate_binary(ctx.expression(0), ctx.expression(1), "||", callback)
        }

        return ""
    }

    // helper to process two sides of an operator
    private fun translate_binary(
        left: MiniKotlinParser.ExpressionContext,
        right: MiniKotlinParser.ExpressionContext,
        op: String,
        callback: (String) -> String
    ): String {
        return translate_expression(left) { l ->
            return@translate_expression translate_expression(right) { r ->
                return@translate_expression callback("(" + l + " " + op + " " + r + ")")
            }
        }
    }

    // helper to process a list of arguments recursively
    private fun translate_argument_list(
        args: List<MiniKotlinParser.ExpressionContext>,
        index: Int,
        values: java.util.ArrayList<String>,
        callback: (java.util.ArrayList<String>) -> String
    ): String {

        // if we processed all arguments, return them
        if (index == args.size) {
            return callback(values)
        }
        // process the current one and move to the next
        return translate_expression(args.get(index)) { v ->
            val new_values = java.util.ArrayList(values)
            new_values.add(v)
            return@translate_expression translate_argument_list(args, index + 1, new_values, callback)
        }
    }

    // handling the basic building blocks
    override fun visitIntLiteral(ctx: MiniKotlinParser.IntLiteralContext): String {
        return ctx.INTEGER_LITERAL().text
    }

    override fun visitStringLiteral(ctx: MiniKotlinParser.StringLiteralContext): String {
        return ctx.STRING_LITERAL().text
    }

    override fun visitBoolLiteral(ctx: MiniKotlinParser.BoolLiteralContext): String {
        return ctx.BOOLEAN_LITERAL().text
    }

    override fun visitIdentifierExpr(ctx: MiniKotlinParser.IdentifierExprContext): String {
        return ctx.IDENTIFIER().text
    }

    override fun visitParenExpr(ctx: MiniKotlinParser.ParenExprContext): String {
        return visit(ctx.expression())
    }

    // helper to map minkotlin types to java types
    private fun map_type(ctx: MiniKotlinParser.TypeContext): String {
        if (ctx.INT_TYPE() != null) { return "Integer" }
        if (ctx.BOOLEAN_TYPE() != null) { return "Boolean" }
        if (ctx.STRING_TYPE() != null) { return "String" }
        if (ctx.UNIT_TYPE() != null) { return "Void" }
        return "Object"
    }
}

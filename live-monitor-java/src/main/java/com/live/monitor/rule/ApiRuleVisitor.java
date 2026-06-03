// Generated from com\live\monitor\rule\ApiRule.g4 by ANTLR 4.9.3
package com.live.monitor.rule;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link ApiRuleParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface ApiRuleVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#parse}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParse(ApiRuleParser.ParseContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression(ApiRuleParser.ExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#orExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrExpression(ApiRuleParser.OrExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#andExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndExpression(ApiRuleParser.AndExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#unaryExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnaryExpression(ApiRuleParser.UnaryExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#comparisonExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitComparisonExpression(ApiRuleParser.ComparisonExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimary(ApiRuleParser.PrimaryContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#functionCall}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionCall(ApiRuleParser.FunctionCallContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#argumentList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgumentList(ApiRuleParser.ArgumentListContext ctx);
	/**
	 * Visit a parse tree produced by {@link ApiRuleParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteral(ApiRuleParser.LiteralContext ctx);
}
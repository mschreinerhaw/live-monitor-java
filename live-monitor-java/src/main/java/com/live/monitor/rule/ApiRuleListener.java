// Generated from com\live\monitor\rule\ApiRule.g4 by ANTLR 4.9.3
package com.live.monitor.rule;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link ApiRuleParser}.
 */
public interface ApiRuleListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#parse}.
	 * @param ctx the parse tree
	 */
	void enterParse(ApiRuleParser.ParseContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#parse}.
	 * @param ctx the parse tree
	 */
	void exitParse(ApiRuleParser.ParseContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExpression(ApiRuleParser.ExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExpression(ApiRuleParser.ExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#orExpression}.
	 * @param ctx the parse tree
	 */
	void enterOrExpression(ApiRuleParser.OrExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#orExpression}.
	 * @param ctx the parse tree
	 */
	void exitOrExpression(ApiRuleParser.OrExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#andExpression}.
	 * @param ctx the parse tree
	 */
	void enterAndExpression(ApiRuleParser.AndExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#andExpression}.
	 * @param ctx the parse tree
	 */
	void exitAndExpression(ApiRuleParser.AndExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#unaryExpression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryExpression(ApiRuleParser.UnaryExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#unaryExpression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryExpression(ApiRuleParser.UnaryExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#comparisonExpression}.
	 * @param ctx the parse tree
	 */
	void enterComparisonExpression(ApiRuleParser.ComparisonExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#comparisonExpression}.
	 * @param ctx the parse tree
	 */
	void exitComparisonExpression(ApiRuleParser.ComparisonExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#primary}.
	 * @param ctx the parse tree
	 */
	void enterPrimary(ApiRuleParser.PrimaryContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#primary}.
	 * @param ctx the parse tree
	 */
	void exitPrimary(ApiRuleParser.PrimaryContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void enterFunctionCall(ApiRuleParser.FunctionCallContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#functionCall}.
	 * @param ctx the parse tree
	 */
	void exitFunctionCall(ApiRuleParser.FunctionCallContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#argumentList}.
	 * @param ctx the parse tree
	 */
	void enterArgumentList(ApiRuleParser.ArgumentListContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#argumentList}.
	 * @param ctx the parse tree
	 */
	void exitArgumentList(ApiRuleParser.ArgumentListContext ctx);
	/**
	 * Enter a parse tree produced by {@link ApiRuleParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterLiteral(ApiRuleParser.LiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link ApiRuleParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitLiteral(ApiRuleParser.LiteralContext ctx);
}
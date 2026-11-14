// Generated from com\live\monitor\rule\ApiRule.g4 by ANTLR 4.9.3
package com.live.monitor.rule;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class ApiRuleLexer extends Lexer {
	static { RuntimeMetaData.checkVersion("4.9.3", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		TRUE=1, FALSE=2, NULL=3, AND=4, OR=5, NOT=6, GTE=7, LTE=8, EQ=9, NEQ=10, 
		GT=11, LT=12, LPAREN=13, RPAREN=14, COMMA=15, IDENT=16, NUMBER=17, STRING=18, 
		WS=19;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"TRUE", "FALSE", "NULL", "AND", "OR", "NOT", "GTE", "LTE", "EQ", "NEQ", 
			"GT", "LT", "LPAREN", "RPAREN", "COMMA", "IDENT", "NUMBER", "STRING", 
			"ESC", "HEX", "WS"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'true'", "'false'", "'null'", null, null, null, "'>='", "'<='", 
			"'=='", "'!='", "'>'", "'<'", "'('", "')'", "','"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "TRUE", "FALSE", "NULL", "AND", "OR", "NOT", "GTE", "LTE", "EQ", 
			"NEQ", "GT", "LT", "LPAREN", "RPAREN", "COMMA", "IDENT", "NUMBER", "STRING", 
			"WS"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}


	public ApiRuleLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "ApiRule.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getChannelNames() { return channelNames; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public ATN getATN() { return _ATN; }

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\2\25\u00a4\b\1\4\2"+
		"\t\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4"+
		"\13\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22"+
		"\t\22\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\3\2\3\2\3\2\3\2\3\2\3\3"+
		"\3\3\3\3\3\3\3\3\3\3\3\4\3\4\3\4\3\4\3\4\3\5\3\5\3\5\3\5\3\5\5\5C\n\5"+
		"\3\6\3\6\3\6\3\6\5\6I\n\6\3\7\3\7\3\7\3\7\5\7O\n\7\3\b\3\b\3\b\3\t\3\t"+
		"\3\t\3\n\3\n\3\n\3\13\3\13\3\13\3\f\3\f\3\r\3\r\3\16\3\16\3\17\3\17\3"+
		"\20\3\20\3\21\3\21\7\21i\n\21\f\21\16\21l\13\21\3\22\5\22o\n\22\3\22\6"+
		"\22r\n\22\r\22\16\22s\3\22\3\22\6\22x\n\22\r\22\16\22y\5\22|\n\22\3\23"+
		"\3\23\3\23\7\23\u0081\n\23\f\23\16\23\u0084\13\23\3\23\3\23\3\23\3\23"+
		"\7\23\u008a\n\23\f\23\16\23\u008d\13\23\3\23\5\23\u0090\n\23\3\24\3\24"+
		"\3\24\3\24\3\24\3\24\3\24\3\24\5\24\u009a\n\24\3\25\3\25\3\26\6\26\u009f"+
		"\n\26\r\26\16\26\u00a0\3\26\3\26\2\2\27\3\3\5\4\7\5\t\6\13\7\r\b\17\t"+
		"\21\n\23\13\25\f\27\r\31\16\33\17\35\20\37\21!\22#\23%\24\'\2)\2+\25\3"+
		"\2\n\5\2C\\aac|\6\2\62;C\\aac|\3\2\62;\4\2$$^^\4\2))^^\13\2$$))\61\61"+
		"^^ddhhppttvv\5\2\62;CHch\5\2\13\f\17\17\"\"\2\u00b0\2\3\3\2\2\2\2\5\3"+
		"\2\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13\3\2\2\2\2\r\3\2\2\2\2\17\3\2\2\2\2"+
		"\21\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2\2\2\27\3\2\2\2\2\31\3\2\2\2\2\33\3"+
		"\2\2\2\2\35\3\2\2\2\2\37\3\2\2\2\2!\3\2\2\2\2#\3\2\2\2\2%\3\2\2\2\2+\3"+
		"\2\2\2\3-\3\2\2\2\5\62\3\2\2\2\78\3\2\2\2\tB\3\2\2\2\13H\3\2\2\2\rN\3"+
		"\2\2\2\17P\3\2\2\2\21S\3\2\2\2\23V\3\2\2\2\25Y\3\2\2\2\27\\\3\2\2\2\31"+
		"^\3\2\2\2\33`\3\2\2\2\35b\3\2\2\2\37d\3\2\2\2!f\3\2\2\2#n\3\2\2\2%\u008f"+
		"\3\2\2\2\'\u0091\3\2\2\2)\u009b\3\2\2\2+\u009e\3\2\2\2-.\7v\2\2./\7t\2"+
		"\2/\60\7w\2\2\60\61\7g\2\2\61\4\3\2\2\2\62\63\7h\2\2\63\64\7c\2\2\64\65"+
		"\7n\2\2\65\66\7u\2\2\66\67\7g\2\2\67\6\3\2\2\289\7p\2\29:\7w\2\2:;\7n"+
		"\2\2;<\7n\2\2<\b\3\2\2\2=>\7(\2\2>C\7(\2\2?@\7c\2\2@A\7p\2\2AC\7f\2\2"+
		"B=\3\2\2\2B?\3\2\2\2C\n\3\2\2\2DE\7~\2\2EI\7~\2\2FG\7q\2\2GI\7t\2\2HD"+
		"\3\2\2\2HF\3\2\2\2I\f\3\2\2\2JO\7#\2\2KL\7p\2\2LM\7q\2\2MO\7v\2\2NJ\3"+
		"\2\2\2NK\3\2\2\2O\16\3\2\2\2PQ\7@\2\2QR\7?\2\2R\20\3\2\2\2ST\7>\2\2TU"+
		"\7?\2\2U\22\3\2\2\2VW\7?\2\2WX\7?\2\2X\24\3\2\2\2YZ\7#\2\2Z[\7?\2\2[\26"+
		"\3\2\2\2\\]\7@\2\2]\30\3\2\2\2^_\7>\2\2_\32\3\2\2\2`a\7*\2\2a\34\3\2\2"+
		"\2bc\7+\2\2c\36\3\2\2\2de\7.\2\2e \3\2\2\2fj\t\2\2\2gi\t\3\2\2hg\3\2\2"+
		"\2il\3\2\2\2jh\3\2\2\2jk\3\2\2\2k\"\3\2\2\2lj\3\2\2\2mo\7/\2\2nm\3\2\2"+
		"\2no\3\2\2\2oq\3\2\2\2pr\t\4\2\2qp\3\2\2\2rs\3\2\2\2sq\3\2\2\2st\3\2\2"+
		"\2t{\3\2\2\2uw\7\60\2\2vx\t\4\2\2wv\3\2\2\2xy\3\2\2\2yw\3\2\2\2yz\3\2"+
		"\2\2z|\3\2\2\2{u\3\2\2\2{|\3\2\2\2|$\3\2\2\2}\u0082\7$\2\2~\u0081\5\'"+
		"\24\2\177\u0081\n\5\2\2\u0080~\3\2\2\2\u0080\177\3\2\2\2\u0081\u0084\3"+
		"\2\2\2\u0082\u0080\3\2\2\2\u0082\u0083\3\2\2\2\u0083\u0085\3\2\2\2\u0084"+
		"\u0082\3\2\2\2\u0085\u0090\7$\2\2\u0086\u008b\7)\2\2\u0087\u008a\5\'\24"+
		"\2\u0088\u008a\n\6\2\2\u0089\u0087\3\2\2\2\u0089\u0088\3\2\2\2\u008a\u008d"+
		"\3\2\2\2\u008b\u0089\3\2\2\2\u008b\u008c\3\2\2\2\u008c\u008e\3\2\2\2\u008d"+
		"\u008b\3\2\2\2\u008e\u0090\7)\2\2\u008f}\3\2\2\2\u008f\u0086\3\2\2\2\u0090"+
		"&\3\2\2\2\u0091\u0099\7^\2\2\u0092\u009a\t\7\2\2\u0093\u0094\7w\2\2\u0094"+
		"\u0095\5)\25\2\u0095\u0096\5)\25\2\u0096\u0097\5)\25\2\u0097\u0098\5)"+
		"\25\2\u0098\u009a\3\2\2\2\u0099\u0092\3\2\2\2\u0099\u0093\3\2\2\2\u009a"+
		"(\3\2\2\2\u009b\u009c\t\b\2\2\u009c*\3\2\2\2\u009d\u009f\t\t\2\2\u009e"+
		"\u009d\3\2\2\2\u009f\u00a0\3\2\2\2\u00a0\u009e\3\2\2\2\u00a0\u00a1\3\2"+
		"\2\2\u00a1\u00a2\3\2\2\2\u00a2\u00a3\b\26\2\2\u00a3,\3\2\2\2\22\2BHNj"+
		"nsy{\u0080\u0082\u0089\u008b\u008f\u0099\u00a0\3\b\2\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}
grammar ApiRule;

parse
    : expression EOF
    ;

expression
    : orExpression
    ;

orExpression
    : andExpression (OR andExpression)*
    ;

andExpression
    : unaryExpression (AND unaryExpression)*
    ;

unaryExpression
    : NOT unaryExpression
    | comparisonExpression
    ;

comparisonExpression
    : primary ((GTE | LTE | GT | LT | EQ | NEQ) primary)?
    ;

primary
    : literal
    | functionCall
    | LPAREN expression RPAREN
    ;

functionCall
    : IDENT LPAREN argumentList? RPAREN
    ;

argumentList
    : expression (COMMA expression)*
    ;

literal
    : NUMBER
    | STRING
    | TRUE
    | FALSE
    | NULL
    ;

TRUE: 'true';
FALSE: 'false';
NULL: 'null';
AND: '&&' | 'and';
OR: '||' | 'or';
NOT: '!' | 'not';
GTE: '>=';
LTE: '<=';
EQ: '==';
NEQ: '!=';
GT: '>';
LT: '<';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
IDENT: [a-zA-Z_] [a-zA-Z_0-9]*;
NUMBER: '-'? [0-9]+ ('.' [0-9]+)?;
STRING: '"' (ESC | ~["\\])* '"' | '\'' (ESC | ~['\\])* '\'';

fragment ESC
    : '\\' (["'\\/bfnrt] | 'u' HEX HEX HEX HEX)
    ;

fragment HEX
    : [0-9a-fA-F]
    ;

WS: [ \t\r\n]+ -> skip;

package tora.parser;

import gw.lang.reflect.IMethodInfo;
import gw.lang.reflect.IType;
import gw.lang.reflect.TypeSystem;
import tora.parser.tree.*;

public class Parser
{
  private ClassNode _classNode;
  private ProgramNode _programNode;
  private Tokenizer _tokenizer;
  private Tokenizer.Token _currentToken, _nextToken;

  //Constructor sets the src from which the parser reads
  public Parser(Tokenizer tokenizer){
    _tokenizer = tokenizer;
    _programNode = new ProgramNode();
  }

  public boolean isES6Class() {
    return _classNode != null;
  }

  public Node parse() {
    nextToken();
    //Can only import classes at top of program
    parseImports();
    parseClassStatement();
    if (!isES6Class() && !match(TokenType.EOF)) _programNode.addChild(parseRestOfProgram());
    return _programNode;
  }


  private void parseClassStatement()
  {
    if( match(TokenType.CLASS))
    {
      //parse class name
      nextToken();
      Tokenizer.Token className = _currentToken;
      skip(match(TokenType.IDENTIFIER));
      _classNode = new ClassNode( className.getValue() );
      _programNode.addChild(_classNode);
      //parse any super classes
      if(matchKeyword("extends")) {
        skip(matchKeyword("extends"));
        _classNode.setSuperClass(_currentToken.getValue());
        skip(match(TokenType.IDENTIFIER));
      }
      //parse class body
      skip(match('{'));
      parseClassBody(className.getValue());
      skip(match('}'));
      Tokenizer.Token end = _currentToken;
      _classNode.setTokens(className, end);
    }
  }

  private void parseImports() {
    while (matchKeyword("import") && !match(TokenType.EOF)) {
      _programNode.addChild(parseImport());
      if (match(';')) nextToken(); //TODO: learn semi-colon syntax
    }
  }


  protected ImportNode parseImport() {
    Tokenizer.Token start = _currentToken;
    skip(matchKeyword("import"));
    StringBuilder packageName = new StringBuilder();
    Matcher matcher = () -> match(TokenType.IDENTIFIER);
    while (matcher.match()) {
      concatToken(packageName);
      if (match(TokenType.IDENTIFIER)) matcher = () -> match('.');
      if (match('.')) matcher = () -> match(TokenType.IDENTIFIER);
      nextToken();
    }
    return new ImportNode(packageName.toString());
  }

  private void parseClassBody(String className)
  {
    while(!match('}') && !match(TokenType.EOF)) {
      if (matchClassKeyword("constructor")) {
        _classNode.addChild(parseConstructor(className));
      } else if (matchClassKeyword("static")) { //properties and functions can both be static
        Tokenizer.Token staticToken = _currentToken;
        nextToken();
        if (matchClassKeyword("get") || matchClassKeyword("set")) {
          _classNode.addChild(parseStaticProperty(className, staticToken));
        } else {
          _classNode.addChild(parseStaticFunction(className, staticToken));
        }
      } else if (matchClassKeyword("get") || matchClassKeyword("set")) {
        _classNode.addChild(parseProperty(className));
      } else if (match(TokenType.IDENTIFIER)) {
        _classNode.addChild(parseFunction(className));
      } else {
        error("Unexpected token: " + _currentToken.toString());
        nextToken();
      }
    }
  }

  private ConstructorNode parseConstructor(String className) {
    Tokenizer.Token start = _currentToken; //'constructor'
    skip(matchClassKeyword("constructor"));

    ParameterNode params = parseParams();
    FunctionBodyNode body = parseFunctionBody(className, false);

    Tokenizer.Token end = _currentToken;
    nextToken();
    ConstructorNode constructorNode = new ConstructorNode(className, className);
    constructorNode.setTokens(start, end);
    constructorNode.addChild(params);
    constructorNode.addChild(body);
    return constructorNode;
  }

  private FunctionNode parseStaticFunction(String className, Tokenizer.Token staticToken) {
    FunctionNode functionNode = parseFunction(className);
    functionNode.setTokens(staticToken, functionNode.getEnd());
    functionNode.setStatic(true);
    return functionNode;
  }

  private FunctionNode parseFunction(String className) {
    Tokenizer.Token start = _currentToken; //Name of function
    String functionName = start.getValue();
    boolean isOverrideFunction = isOverrideFunction(functionName);
    skip(match(TokenType.IDENTIFIER));

    ParameterNode params = parseParams();
    String returnType = parseReturnType();

    FunctionBodyNode body = parseFunctionBody(functionName, isOverrideFunction);

    FunctionNode functionNode = new FunctionNode(functionName, className, false);
    functionNode.setReturnType(returnType);
    functionNode.setOverride(isOverrideFunction);
    functionNode.setTokens(start, _currentToken);
    functionNode.addChild(params);
    functionNode.addChild(body);
    nextToken();
    return functionNode;

  }


  private PropertyNode parseStaticProperty(String className, Tokenizer.Token staticToken) {
    PropertyNode propertyNode = parseProperty(className);
    propertyNode.setTokens(staticToken, propertyNode.getEnd());
    propertyNode.setStatic(true);
    return propertyNode;
  }


  private PropertyNode parseProperty(String className) {
    Tokenizer.Token start = _currentToken; //'get' or 'set'
    boolean isSetter = matchClassKeyword("set");
    skip(matchClassKeyword("get") || matchClassKeyword("set"));
    String functionName = _currentToken.getValue();
    skip(match(TokenType.IDENTIFIER));

    ParameterNode params = parseParams();
    FunctionBodyNode body = parseFunctionBody(functionName, false);

    PropertyNode node = new PropertyNode(functionName, className, false, isSetter);
    node.setTokens(start, _currentToken);
    node.addChild(params);
    node.addChild(body);
    nextToken();
    return node;
  }

  /*Concats parameters into a node*/
  protected ParameterNode parseParams() {
    skip(match('('));
    ParameterNode paramNode = new ParameterNode();

    StringBuilder val = new StringBuilder();
    Matcher matcher = () -> match(')') || match(TokenType.IDENTIFIER);
    expect(matcher);
    while (!match(')') && !match(TokenType.EOF)) {
      if (match(TokenType.IDENTIFIER)) {
        matcher = () -> match(',') || match(')') || match(':'); //ending paren or comma can follow identifier
        String paramValue = _currentToken.getValue();
        paramNode.addParam(paramValue, parseType());
      } else if (match(',')) {
        matcher = () -> match(TokenType.IDENTIFIER); //identifier must follow commas
      }
      nextToken();
      expect(matcher);
    }
    skip(match(')'));

    return paramNode;
  }

  /* Function: parseReturnType
     -------------------------
     Sees if there is a return type of the format function() : returnType {}
      and returns dynamic.Dynamic if none is specified
   */
  private String parseReturnType() {
    if(_currentToken.getValue().equals(":")) {
      nextToken();
//      if(match(TokenType.IDENTIFIER)) {
        String returnType = _currentToken.getValue();
        nextToken();
        return returnType;
//      }
    }
    return "dynamic.Dynamic";

  }

  /* Function: parseType()
     ---------------------
     Peeks at the next section of the argument list to see if the code
     specifies a return type
   */
  private String parseType() {
    if(peekToken().getValue().equals(":")) {
      nextToken();
//      if(match(TokenType.IDENTIFIER)) {
        nextToken();
        return _currentToken.getValue();
//      }
    }
    return null;

  }

  private FunctionBodyNode parseFunctionBody(String functionName, boolean isOverrideFunction) {
    FunctionBodyNode bodyNode = new FunctionBodyNode(functionName);
    FillerNode fillerNode = new FillerNode();
    fillerNode.context.inOverrideFunction = isOverrideFunction;
    expect(match('{'));
    fillerNode.concatToken(_currentToken); // '{'
    int curlyCount = 1;
    while (curlyCount > 0 && !match(TokenType.EOF)) {
      nextAnyToken();
      if (match('}')) curlyCount--;
      if (match('{')) curlyCount++;

      if (matchOperator("=>")){
        skip(matchOperator("=>"));
        ArrowExpressionNode arrowNode = new ArrowExpressionNode();
        arrowNode.extractParams(fillerNode);
        //Add filler node and create a new one
        bodyNode.addChild(fillerNode);
        fillerNode = new FillerNode();
        if (match('{')) {
          arrowNode.addChild(parseFunctionBody(null, false));
          expect(match('}'));
        } else {
          arrowNode.addChild(parseExpression());
        }
        bodyNode.addChild(arrowNode);
      } else {
        fillerNode.concatToken(_currentToken);
      }
    }
    bodyNode.addChild(fillerNode);
    return bodyNode;
  }

  private FillerNode parseExpression() {
    FillerNode fillerNode = new FillerNode();
    while (!(match(TokenType.EOF) || match(';'))) {
      fillerNode.concatToken(_currentToken);
      nextAnyToken();
    }
    return fillerNode;
  }

  protected FillerNode parseFillerUntil(Matcher matcher) {
    FillerNode fillerNode = new FillerNode();
    while (!(match(TokenType.EOF) || matcher.match())) {
      fillerNode.concatToken(_currentToken);
      nextAnyToken();
    }
    return fillerNode;
  }

  private FillerNode parseRestOfProgram() {
    FillerNode fillerNode = new FillerNode();
    while (!match(TokenType.EOF)) {
      fillerNode.concatToken(_currentToken);
      nextAnyToken();
    }
    return fillerNode;
  }

  //========================================================================================
  // Utilities
  //========================================================================================

  /*Concats current token to a string builder*/
  private void concatToken (StringBuilder val) {
    val.append(_currentToken.getValue());
  }

  //Used to create lambda functions for matching tokens
  protected interface Matcher {
    boolean match();
  }

  protected void expect(Matcher matcher) {
    if (!matcher.match()) expect(false);
  }

  protected void expect(boolean b) {
    if (!b) error("Unexpected Token: " + _currentToken.toString());
  }

  /*assert an expectation for the current token then skip*/
  protected void skip(boolean b) {
    expect(b);
    nextToken();
  }

  private void error(String errorMsg) {
    _programNode.addError(new Error(errorMsg));
  }

  /*Match single character punctuation*/
  protected boolean match( char c )
  {
    return match(TokenType.PUNCTUATION, String.valueOf(c));
  }

  /*Match operators*/
  protected boolean matchOperator(String val )
  {
    return match(TokenType.OPERATOR, val);
  }


  /*Match reserved keywords only*/
  protected boolean matchKeyword(String val)
  {
    return match(TokenType.KEYWORD, val);
  }

  /*Matches conditional keywords such as "constructor", which are sometimes keywords within a class
   and identifiers otherwise*/
  protected boolean matchClassKeyword(String val)
  {
    if (!match(TokenType.IDENTIFIER, val)) return false;
    //If these class keywords aren't followed by an identifier, treat them as regular identifiers
    if ((val.equals("static") || val.equals("get") || val.equals("set")) &&
            peekToken().getType() != TokenType.IDENTIFIER) return false;
    return  true;
  }

  protected boolean match(TokenType type, String val) {
    return match(type) && _currentToken.getValue().equals(val);
  }

  protected boolean match( TokenType type )
  {
    return (_currentToken.getType() == type);
  }


  private Tokenizer.Token peekToken() {
    if (_nextToken == null || _nextToken.getOffset() <= _currentToken.getOffset()) {
      _nextToken = _tokenizer.nextNonWhiteSpace();
    }
    return _nextToken;
  }

  protected Tokenizer.Token currToken() {
    return _currentToken;
  }

  private boolean isOverrideFunction(String functionName) {
    String packageName = _programNode.getPackageFromClassName(_classNode.getSuperClass());
    if (packageName == null) return false;
    IType superType = TypeSystem.getByFullName(packageName);
    if (superType == null) return false;
    for (IMethodInfo method : superType.getTypeInfo().getMethods()) {
      if (method.getDisplayName().equals(functionName)) return true;
    }
    return false;
  }

  /*Move current token to the next token (including whitespace)*/
  private void nextAnyToken() {
    _currentToken = _tokenizer.next();
  }

  /*Move current token to the next non-whitespace token*/
  protected void nextToken()
  {
    if (_currentToken == null || _nextToken == null || _currentToken.getOffset() >= _nextToken.getOffset()) {
      _currentToken = _tokenizer.nextNonWhiteSpace();
    } else {
      _currentToken = _nextToken;
    }
  }
}
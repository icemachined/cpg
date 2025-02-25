/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */
package de.fraunhofer.aisec.cpg.frontends.java;

import static de.fraunhofer.aisec.cpg.graph.DeclarationBuilderKt.*;
import static de.fraunhofer.aisec.cpg.graph.NodeBuilderKt.*;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import de.fraunhofer.aisec.cpg.frontends.Handler;
import de.fraunhofer.aisec.cpg.graph.ProblemNode;
import de.fraunhofer.aisec.cpg.graph.TypeManager;
import de.fraunhofer.aisec.cpg.graph.declarations.*;
import de.fraunhofer.aisec.cpg.graph.statements.CompoundStatement;
import de.fraunhofer.aisec.cpg.graph.statements.expressions.Expression;
import de.fraunhofer.aisec.cpg.graph.types.*;
import de.fraunhofer.aisec.cpg.passes.scopes.RecordScope;
import de.fraunhofer.aisec.cpg.sarif.PhysicalLocation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class DeclarationHandler
    extends Handler<Declaration, BodyDeclaration<?>, JavaLanguageFrontend> {

  public DeclarationHandler(JavaLanguageFrontend lang) {
    super(ProblemDeclaration::new, lang);
    map.put(
        com.github.javaparser.ast.body.MethodDeclaration.class,
        decl -> handleMethodDeclaration((com.github.javaparser.ast.body.MethodDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.ConstructorDeclaration.class,
        decl ->
            handleConstructorDeclaration(
                (com.github.javaparser.ast.body.ConstructorDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class,
        decl ->
            handleClassOrInterfaceDeclaration(
                (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.FieldDeclaration.class,
        decl -> handleFieldDeclaration((com.github.javaparser.ast.body.FieldDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.EnumDeclaration.class,
        decl -> handleEnumDeclaration((com.github.javaparser.ast.body.EnumDeclaration) decl));
    map.put(
        com.github.javaparser.ast.body.EnumConstantDeclaration.class,
        decl ->
            handleEnumConstantDeclaration(
                (com.github.javaparser.ast.body.EnumConstantDeclaration) decl));
  }

  private static void addImplicitReturn(BlockStmt body) {
    NodeList<Statement> statements = body.getStatements();

    // get the last statement
    Statement lastStatement = null;
    if (!statements.isEmpty()) {
      lastStatement = statements.get(statements.size() - 1);
    }
    // make sure, method contains a return statement
    if (lastStatement == null || !lastStatement.isReturnStmt()) {
      body.addStatement(new ReturnStmt());
    }
  }

  public de.fraunhofer.aisec.cpg.graph.declarations.ConstructorDeclaration
      handleConstructorDeclaration(
          com.github.javaparser.ast.body.ConstructorDeclaration constructorDecl) {
    ResolvedConstructorDeclaration resolvedConstructor = constructorDecl.resolve();

    var currentRecordDecl = frontend.getScopeManager().getCurrentRecord();
    de.fraunhofer.aisec.cpg.graph.declarations.ConstructorDeclaration declaration =
        newConstructorDeclaration(
            this, resolvedConstructor.getName(), constructorDecl.toString(), currentRecordDecl);
    frontend.getScopeManager().addDeclaration(declaration);

    frontend.getScopeManager().enterScope(declaration);

    createMethodReceiver(currentRecordDecl, declaration);

    declaration.addThrowTypes(
        constructorDecl.getThrownExceptions().stream()
            .map(type -> parseType(this, type.asString()))
            .collect(Collectors.toList()));

    for (Parameter parameter : constructorDecl.getParameters()) {
      ParamVariableDeclaration param =
          newParamVariableDeclaration(
              this,
              parameter.getNameAsString(),
              this.frontend.getTypeAsGoodAsPossible(parameter, parameter.resolve()),
              parameter.isVarArgs());

      declaration.addParameter(param);

      frontend.setCodeAndLocation(param, parameter);
      frontend.getScopeManager().addDeclaration(param);
    }

    Type type =
        parseType(
            this,
            frontend
                .getScopeManager()
                .firstScopeOrNull(RecordScope.class::isInstance)
                .getAstNode()
                .getName());
    declaration.setType(type);

    // check, if constructor has body (i.e. its not abstract or something)
    BlockStmt body = constructorDecl.getBody();

    addImplicitReturn(body);

    declaration.setBody(this.frontend.getStatementHandler().handle(body));

    frontend.processAnnotations(declaration, constructorDecl);

    frontend.getScopeManager().leaveScope(declaration);
    return declaration;
  }

  public de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration handleMethodDeclaration(
      com.github.javaparser.ast.body.MethodDeclaration methodDecl) {
    ResolvedMethodDeclaration resolvedMethod = methodDecl.resolve();

    var currentRecordDecl = frontend.getScopeManager().getCurrentRecord();

    de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration functionDeclaration =
        newMethodDeclaration(
            this,
            resolvedMethod.getName(),
            methodDecl.toString(),
            methodDecl.isStatic(),
            currentRecordDecl);

    frontend.getScopeManager().enterScope(functionDeclaration);

    createMethodReceiver(currentRecordDecl, functionDeclaration);

    functionDeclaration.addThrowTypes(
        methodDecl.getThrownExceptions().stream()
            .map(type -> parseType(this, type.asString()))
            .collect(Collectors.toList()));

    for (Parameter parameter : methodDecl.getParameters()) {
      Type resolvedType =
          TypeManager.getInstance()
              .getTypeParameter(
                  functionDeclaration.getRecordDeclaration(), parameter.getType().toString());
      if (resolvedType == null) {
        resolvedType = this.frontend.getTypeAsGoodAsPossible(parameter, parameter.resolve());
      }

      ParamVariableDeclaration param =
          newParamVariableDeclaration(
              this, parameter.getNameAsString(), resolvedType, parameter.isVarArgs());

      functionDeclaration.addParameter(param);
      frontend.setCodeAndLocation(param, parameter);

      frontend.processAnnotations(param, parameter);

      frontend.getScopeManager().addDeclaration(param);
    }

    var returnTypes =
        List.of(this.frontend.getReturnTypeAsGoodAsPossible(methodDecl, resolvedMethod));
    functionDeclaration.setReturnTypes(returnTypes);

    var type = FunctionType.computeType(functionDeclaration);
    functionDeclaration.setType(type);

    // check, if method has body (i.e. its not abstract or something)
    Optional<BlockStmt> o = methodDecl.getBody();

    if (o.isEmpty()) {
      frontend.getScopeManager().leaveScope(functionDeclaration);
      return functionDeclaration;
    }

    BlockStmt body = o.get();

    addImplicitReturn(body);

    functionDeclaration.setBody(this.frontend.getStatementHandler().handle(body));

    frontend.processAnnotations(functionDeclaration, methodDecl);

    frontend.getScopeManager().leaveScope(functionDeclaration);
    return functionDeclaration;
  }

  private void createMethodReceiver(
      RecordDeclaration recordDecl, MethodDeclaration functionDeclaration) {
    // create the receiver
    var receiver =
        newVariableDeclaration(
            this,
            "this",
            recordDecl != null
                ? parseType(this, recordDecl.getName())
                : UnknownType.getUnknownType(getLanguage()),
            "this",
            false);

    functionDeclaration.setReceiver(receiver);

    frontend.getScopeManager().addDeclaration(receiver);
  }

  public RecordDeclaration handleClassOrInterfaceDeclaration(
      ClassOrInterfaceDeclaration classInterDecl) {
    // TODO: support other kinds, such as interfaces
    String fqn = classInterDecl.getNameAsString();

    // Todo adapt name using a new type of scope "Namespace/Package scope"
    // if (packageDeclaration != null) {
    //  name = packageDeclaration.getNameAsString() + "." + name;
    // }
    fqn = getAbsoluteName(fqn);

    // add a type declaration
    RecordDeclaration recordDeclaration =
        newRecordDeclaration(this, fqn, "class", null, classInterDecl);
    recordDeclaration.setSuperClasses(
        classInterDecl.getExtendedTypes().stream()
            .map(this.frontend::getTypeAsGoodAsPossible)
            .collect(Collectors.toList()));
    recordDeclaration.setImplementedInterfaces(
        classInterDecl.getImplementedTypes().stream()
            .map(this.frontend::getTypeAsGoodAsPossible)
            .collect(Collectors.toList()));

    TypeManager.getInstance()
        .addTypeParameter(
            recordDeclaration,
            classInterDecl.getTypeParameters().stream()
                .map(t -> new ParameterizedType(t.getNameAsString(), getLanguage()))
                .collect(Collectors.toList()));

    Map<Boolean, List<String>> partitioned =
        this.frontend.getContext().getImports().stream()
            .collect(
                Collectors.partitioningBy(
                    ImportDeclaration::isStatic,
                    Collectors.mapping(
                        i -> {
                          String iName = i.getNameAsString();
                          // we need to ensure that x.* imports really preserve the asterisk!
                          if (i.isAsterisk() && !iName.endsWith(".*")) {
                            iName += ".*";
                          }
                          return iName;
                        },
                        Collectors.toList())));
    recordDeclaration.setStaticImportStatements(partitioned.get(true));
    recordDeclaration.setImportStatements(partitioned.get(false));

    frontend.getScopeManager().enterScope(recordDeclaration);

    // TODO: 'this' identifier for multiple instances?
    for (BodyDeclaration<?> decl : classInterDecl.getMembers()) {
      if (decl instanceof com.github.javaparser.ast.body.FieldDeclaration) {
        handle(decl); // will be added via the scopemanager
      } else if (decl instanceof com.github.javaparser.ast.body.MethodDeclaration) {
        de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration md =
            (de.fraunhofer.aisec.cpg.graph.declarations.MethodDeclaration) handle(decl);
        recordDeclaration.addMethod(md);
      } else if (decl instanceof com.github.javaparser.ast.body.ConstructorDeclaration) {
        de.fraunhofer.aisec.cpg.graph.declarations.ConstructorDeclaration c =
            (de.fraunhofer.aisec.cpg.graph.declarations.ConstructorDeclaration) handle(decl);
        recordDeclaration.addConstructor(c);
      } else if (decl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) {
        recordDeclaration.addDeclaration(handle(decl));
      } else if (decl instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
        InitializerDeclaration id = (InitializerDeclaration) decl;
        CompoundStatement initializerBlock =
            frontend.getStatementHandler().handleBlockStatement(id.getBody());
        initializerBlock.setStaticBlock(id.isStatic());
        recordDeclaration.addStatement(initializerBlock);
      } else {
        log.debug(
            "Member {} of type {} is something that we do not parse yet: {}",
            decl,
            recordDeclaration.getName(),
            decl.getClass().getSimpleName());
      }
    }

    if (recordDeclaration.getConstructors().isEmpty()) {
      de.fraunhofer.aisec.cpg.graph.declarations.ConstructorDeclaration constructorDeclaration =
          newConstructorDeclaration(
              this, recordDeclaration.getName(), recordDeclaration.getName(), recordDeclaration);
      recordDeclaration.addConstructor(constructorDeclaration);
      frontend.getScopeManager().addDeclaration(constructorDeclaration);
    }

    frontend.processAnnotations(recordDeclaration, classInterDecl);

    frontend.getScopeManager().leaveScope(recordDeclaration);

    // We need special handling if this is a so called "inner class". In this case we need to store
    // a "this" reference to the outer class, so methods can use a "qualified this"
    // (OuterClass.this.someFunction()). This is the same as the java compiler does. The reference
    // is stored as an implicit field.
    if (frontend.getScopeManager().getCurrentScope() instanceof RecordScope) {
      var scope = (RecordScope) frontend.getScopeManager().getCurrentScope();

      var field =
          newFieldDeclaration(
              this,
              scope.getSimpleName() + ".this",
              parseType(this, scope.getScopedName()),
              null,
              null,
              null,
              false);
      field.setImplicit(true);

      frontend.getScopeManager().enterScope(recordDeclaration);
      frontend.getScopeManager().addDeclaration(field);
      frontend.getScopeManager().leaveScope(recordDeclaration);
    }

    return recordDeclaration;
  }

  public de.fraunhofer.aisec.cpg.graph.declarations.FieldDeclaration handleFieldDeclaration(
      com.github.javaparser.ast.body.FieldDeclaration fieldDecl) {

    // TODO: can  field have more than one variable?
    VariableDeclarator variable = fieldDecl.getVariable(0);
    List<String> modifiers =
        fieldDecl.getModifiers().stream()
            .map(modifier -> modifier.getKeyword().asString())
            .collect(Collectors.toList());

    String joinedModifiers = String.join(" ", modifiers) + " ";

    PhysicalLocation location = this.frontend.getLocationFromRawNode(fieldDecl);

    Expression initializer =
        (Expression)
            variable
                .getInitializer()
                .map(this.frontend.getExpressionHandler()::handle)
                .orElse(null);
    Type type;
    try {
      // Resolve type first with ParameterizedType
      type =
          TypeManager.getInstance()
              .getTypeParameter(
                  this.frontend.getScopeManager().getCurrentRecord(),
                  variable.resolve().getType().describe());
      if (type == null) {
        type = parseType(this, joinedModifiers + variable.resolve().getType().describe());
      }
    } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
      String t = this.frontend.recoverTypeFromUnsolvedException(e);
      if (t == null) {
        log.warn("Could not resolve type for {}", variable);
        type = parseType(this, joinedModifiers + variable.getType().asString());
      } else {
        type = parseType(this, joinedModifiers + t);
        type.setTypeOrigin(Type.Origin.GUESSED);
      }
    }
    de.fraunhofer.aisec.cpg.graph.declarations.FieldDeclaration fieldDeclaration =
        newFieldDeclaration(
            this,
            variable.getName().asString(),
            type,
            modifiers,
            variable.toString(),
            location,
            initializer,
            false);
    frontend.getScopeManager().addDeclaration(fieldDeclaration);

    this.frontend.processAnnotations(fieldDeclaration, fieldDecl);

    return fieldDeclaration;
  }

  public de.fraunhofer.aisec.cpg.graph.declarations.EnumDeclaration handleEnumDeclaration(
      com.github.javaparser.ast.body.EnumDeclaration enumDecl) {
    String name = getAbsoluteName(enumDecl.getNameAsString());
    PhysicalLocation location = this.frontend.getLocationFromRawNode(enumDecl);

    de.fraunhofer.aisec.cpg.graph.declarations.EnumDeclaration enumDeclaration =
        newEnumDeclaration(this, name, enumDecl.toString(), location);
    List<de.fraunhofer.aisec.cpg.graph.declarations.EnumConstantDeclaration> entries =
        enumDecl.getEntries().stream()
            .map(
                e -> (de.fraunhofer.aisec.cpg.graph.declarations.EnumConstantDeclaration) handle(e))
            .collect(Collectors.toList());
    entries.forEach(e -> e.setType(parseType(this, enumDeclaration.getName())));
    enumDeclaration.setEntries(entries);

    List<Type> superTypes =
        enumDecl.getImplementedTypes().stream()
            .map(this.frontend::getTypeAsGoodAsPossible)
            .collect(Collectors.toList());
    enumDeclaration.setSuperTypes(superTypes);

    return enumDeclaration;
  }

  /* Not so sure about the place of Annotations in the CPG currently */

  public de.fraunhofer.aisec.cpg.graph.declarations.EnumConstantDeclaration
      handleEnumConstantDeclaration(
          com.github.javaparser.ast.body.EnumConstantDeclaration enumConstDecl) {
    return newEnumConstantDeclaration(
        this,
        enumConstDecl.getNameAsString(),
        enumConstDecl.toString(),
        this.frontend.getLocationFromRawNode(enumConstDecl));
  }

  public Declaration /* TODO refine return type*/ handleAnnotationDeclaration(
      AnnotationDeclaration annotationConstDecl) {
    return new ProblemDeclaration(
        "AnnotationDeclaration not supported yet", ProblemNode.ProblemType.TRANSLATION);
  }

  public Declaration /* TODO refine return type*/ handleAnnotationMemberDeclaration(
      AnnotationMemberDeclaration annotationMemberDecl) {
    return new ProblemDeclaration(
        "AnnotationMemberDeclaration not supported yet", ProblemNode.ProblemType.TRANSLATION);
  }

  private String getAbsoluteName(String name) {
    String prefix = frontend.getScopeManager().getCurrentNamePrefix();
    name = (prefix.length() > 0 ? prefix + getLanguage().getNamespaceDelimiter() : "") + name;
    return name;
  }
}

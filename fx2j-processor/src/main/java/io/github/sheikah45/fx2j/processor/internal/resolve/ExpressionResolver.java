package io.github.sheikah45.fx2j.processor.internal.resolve;

import io.github.sheikah45.fx2j.parser.property.Expression;
import io.github.sheikah45.fx2j.processor.internal.code.CodeValue;
import io.github.sheikah45.fx2j.processor.internal.code.CodeValues;
import io.github.sheikah45.fx2j.processor.internal.model.ExpressionResult;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ExpressionResolver {

    private static final String BINDINGS_CLASS_NAME = "javafx.beans.binding.Bindings";

    private final TypeResolver typeResolver;
    private final MethodResolver methodResolver;
    private final NameResolver nameResolver;

    ExpressionResolver(TypeResolver typeResolver, MethodResolver methodResolver, NameResolver nameResolver) {
        this.typeResolver = typeResolver;
        this.methodResolver = methodResolver;
        this.nameResolver = nameResolver;
    }

    public ExpressionResult resolveExpression(Expression value) {
        return switch (value) {
            case Expression.Null() -> new ExpressionResult(Object.class, CodeValues.nullValue(), List.of());
            case Expression.Whole(long val) when val > Integer.MAX_VALUE || val < Integer.MIN_VALUE ->
                    new ExpressionResult(long.class, CodeValues.literal(val), List.of());
            case Expression.Whole(long val) ->
                    new ExpressionResult(int.class, CodeValues.literal((int) val), List.of());
            case Expression.Fraction(double val) when val > Float.MAX_VALUE || val < Float.MIN_VALUE ->
                    new ExpressionResult(double.class, CodeValues.literal(val), List.of());
            case Expression.Fraction(double val) ->
                    new ExpressionResult(float.class, CodeValues.literal((float) val), List.of());
            case Expression.Boolean(boolean val) ->
                    new ExpressionResult(boolean.class, CodeValues.literal(val), List.of());
            case Expression.String(String val) ->
                    new ExpressionResult(String.class, CodeValues.literal(val), List.of());
            case Expression.Variable(String name) ->
                    new ExpressionResult(nameResolver.resolveTypeById(name), CodeValues.variable(name), List.of());
            case Expression.PropertyRead(Expression expression, String property) -> {
                ExpressionResult expressionResult = resolveExpression(expression);
                List<CodeValue.Declaration> initializers = new ArrayList<>(expressionResult.initializers());

                Method readProperty = methodResolver.resolveProperty(expressionResult.type(), property)
                                                    .orElseThrow(() -> new IllegalArgumentException(
                                                            "No property found for expression binding %s".formatted(
                                                                    property)));
                Type valueType = readProperty.getGenericReturnType();
                String identifier = nameResolver.resolveUniqueName(valueType);

                initializers.add(CodeValues.declaration(valueType, identifier,
                                                        CodeValues.methodCall(expressionResult.value(), readProperty)));
                yield new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
            }
            case Expression.MethodCall(
                    Expression expression, String methodName, List<Expression> args
            ) -> {
                ExpressionResult expressionResult = resolveExpression(expression);
                List<CodeValue.Declaration> initializers = new ArrayList<>(expressionResult.initializers());
                List<Type> parameterTypes = new ArrayList<>();
                List<CodeValue.Expression> methodArgs = new ArrayList<>();
                for (Expression arg : args) {
                    ExpressionResult argResult = resolveExpression(arg);
                    parameterTypes.add(argResult.type());
                    methodArgs.add(argResult.value());
                    initializers.addAll(argResult.initializers());
                }

                Method method = methodResolver.findMethod(expressionResult.type(), methodName,
                                                          parameterTypes.toArray(Type[]::new))
                                              .orElseThrow(() -> new IllegalArgumentException(
                                                      "No method found for class %s method name %s and parameters %s".formatted(
                                                              expressionResult.type(), methodName, parameterTypes)));
                Type valueType = method.getGenericReturnType();
                String identifier = nameResolver.resolveUniqueName(valueType);

                initializers.add(CodeValues.declaration(valueType, identifier,
                                                        CodeValues.methodCall(expressionResult.value(), method,
                                                                              methodArgs.toArray())));
                yield new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
            }
            case Expression.CollectionAccess(Expression expression, Expression key) -> {
                ExpressionResult expressionResult = resolveExpression(expression);
                ExpressionResult keyResult = resolveExpression(key);
                List<CodeValue.Declaration> initializers = new ArrayList<>();
                initializers.addAll(expressionResult.initializers());
                initializers.addAll(keyResult.initializers());
                Class<?> bindingsClass = typeResolver.resolve(BINDINGS_CLASS_NAME);
                Method valueAtMethod = methodResolver.findMethod(bindingsClass, "valueAt", expressionResult.type(),
                                                                 keyResult.type())
                                                     .orElseThrow(() -> new IllegalArgumentException(
                                                             "Unable to find method to access collection"));
                Type valueType = valueAtMethod.getGenericReturnType();
                String identifier = nameResolver.resolveUniqueName(valueType);
                initializers.add(CodeValues.declaration(valueType, identifier,
                                                        CodeValues.methodCall(valueAtMethod, expressionResult.value(),
                                                                              keyResult.value())));
                yield new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
            }
            case Expression.Add(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "add", "concat");
            case Expression.Subtract(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "subtract");
            case Expression.Multiply(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "multiply");
            case Expression.Divide(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "divide");
            case Expression.GreaterThan(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "greaterThan");
            case Expression.GreaterThanEqual(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "greaterThanOrEqualTo", "greaterThanOrEqual");
            case Expression.LessThan(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "lessThan");
            case Expression.LessThanEqual(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "lessThanOrEqualTo", "lessThanOrEqual");
            case Expression.Equal(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "isEqualTo", "equal");
            case Expression.NotEqual(Expression left, Expression right) ->
                    computeExpressionWithMethod(left, right, "isNotEqualTo", "notEqual");
            case Expression.And(Expression left, Expression right) -> computeExpressionWithMethod(left, right, "and");
            case Expression.Or(Expression left, Expression right) -> computeExpressionWithMethod(left, right, "or");
            case Expression.Invert(Expression expression) -> computeExpressionWithMethod(expression, "not");
            case Expression.Negate(Expression expression) -> computeExpressionWithMethod(expression, "negate");
            case Expression.Modulo ignored ->
                    throw new UnsupportedOperationException("Modulo operation in expression not supported");
        };
    }

    private ExpressionResult computeExpressionWithMethod(Expression left, Expression right, String... methodNames) {
        ExpressionResult leftResult = resolveExpression(left);
        ExpressionResult rightResult = resolveExpression(right);
        List<CodeValue.Declaration> initializers = new ArrayList<>();
        initializers.addAll(leftResult.initializers());
        initializers.addAll(rightResult.initializers());

        Method directMethod = Arrays.stream(methodNames)
                                    .map(methodName -> methodResolver.findMethod(leftResult.type(), methodName,
                                                                                 rightResult.type()))
                                    .flatMap(Optional::stream)
                                    .findFirst()
                                    .orElse(null);
        if (directMethod != null) {
            Type valueType = directMethod.getGenericReturnType();
            String identifier = nameResolver.resolveUniqueName(valueType);
            initializers.add(CodeValues.declaration(valueType, identifier,
                                                    CodeValues.methodCall(leftResult.value(), directMethod,
                                                                          rightResult.value())));
            return new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
        }

        Class<?> bindingsClass = typeResolver.resolve(BINDINGS_CLASS_NAME);
        Method indirectMethod = Arrays.stream(methodNames)
                                      .map(methodName -> methodResolver.findMethod(bindingsClass, methodName,
                                                                                   leftResult.type(),
                                                                                   rightResult.type()))
                                      .flatMap(Optional::stream)
                                      .findFirst()
                                      .orElse(null);
        if (indirectMethod != null) {
            Type valueType = indirectMethod.getGenericReturnType();
            String identifier = nameResolver.resolveUniqueName(valueType);
            initializers.add(CodeValues.declaration(valueType, identifier,
                                                    CodeValues.methodCall(indirectMethod, leftResult.value(),
                                                                          rightResult.value())));
            return new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
        }

        throw new IllegalArgumentException(
                "Cannot %s %s and %s".formatted(String.join(" or ", methodNames), left, right));
    }

    private ExpressionResult computeExpressionWithMethod(Expression value, String methodName) {
        ExpressionResult result = resolveExpression(value);
        List<CodeValue.Declaration> initializers = new ArrayList<>(result.initializers());

        Method directMethod = methodResolver.findMethod(result.type(), methodName).orElse(null);
        if (directMethod != null) {
            Type valueType = directMethod.getGenericReturnType();
            String identifier = nameResolver.resolveUniqueName(valueType);
            initializers.add(
                    CodeValues.declaration(valueType, identifier, CodeValues.methodCall(result.value(), directMethod)));
            return new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
        }

        Class<?> bindingsClass = typeResolver.resolve(BINDINGS_CLASS_NAME);
        Method indirectMethod = methodResolver.findMethod(bindingsClass, methodName, result.type()).orElse(null);
        if (indirectMethod != null) {
            Type valueType = indirectMethod.getGenericReturnType();
            String identifier = nameResolver.resolveUniqueName(valueType);
            initializers.add(CodeValues.declaration(valueType, identifier,
                                                    CodeValues.methodCall(indirectMethod, result.value())));
            return new ExpressionResult(valueType, CodeValues.variable(identifier), initializers);
        }

        throw new IllegalArgumentException("Cannot %s %s".formatted(methodName, value));
    }
}

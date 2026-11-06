package com.sunnysuperman.mountain.checkstyle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

/** Checks the Mountain 3 member ordering convention. */
public class MemberOrderCheck extends AbstractCheck {
    private static final Comparator<Member> MEMBER_ORDER = Comparator.comparingInt(Member::rank)
            .thenComparingInt(Member::visibility).thenComparingInt(Member::accessorOrder);

    @Override
    public int[] getDefaultTokens() {
        return new int[] { TokenTypes.CLASS_DEF, TokenTypes.INTERFACE_DEF, TokenTypes.ENUM_DEF,
                TokenTypes.ANNOTATION_DEF, TokenTypes.RECORD_DEF };
    }

    @Override
    public int[] getAcceptableTokens() {
        return getDefaultTokens();
    }

    @Override
    public int[] getRequiredTokens() {
        return getDefaultTokens();
    }

    @Override
    public void visitToken(DetailAST type) {
        DetailAST objectBlock = type.findFirstToken(TokenTypes.OBJBLOCK);
        if (objectBlock == null) {
            return;
        }

        List<Member> members = new ArrayList<>();
        for (DetailAST child = objectBlock.getFirstChild(); child != null; child = child.getNextSibling()) {
            Member member = Member.from(child, type);
            if (member != null) {
                members.add(member);
            }
        }

        for (int index = 1; index < members.size(); index++) {
            if (MEMBER_ORDER.compare(members.get(index - 1), members.get(index)) > 0) {
                log(members.get(index).ast(), "member.order");
                return;
            }
        }
    }

    private static final class Member {
        private static final int PUBLIC = 0;
        private static final int PROTECTED = 1;
        private static final int PACKAGE = 2;
        private static final int PRIVATE = 3;

        private final DetailAST ast;
        private final int rank;
        private final int visibility;
        private final int accessorOrder;

        private Member(DetailAST ast, int rank, int visibility, int accessorOrder) {
            this.ast = ast;
            this.rank = rank;
            this.visibility = visibility;
            this.accessorOrder = accessorOrder;
        }

        private static Member from(DetailAST ast, DetailAST owner) {
            switch (ast.getType()) {
            case TokenTypes.VARIABLE_DEF:
            case TokenTypes.ENUM_CONSTANT_DEF:
                return fromField(ast, owner);
            case TokenTypes.STATIC_INIT:
                return new Member(ast, 3, PACKAGE, 0);
            case TokenTypes.INSTANCE_INIT:
                return new Member(ast, 4, PACKAGE, 0);
            case TokenTypes.CTOR_DEF:
            case TokenTypes.COMPACT_CTOR_DEF:
                return new Member(ast, 5, visibility(ast, owner), 0);
            case TokenTypes.METHOD_DEF:
                return fromMethod(ast, owner);
            case TokenTypes.ANNOTATION_FIELD_DEF:
                return new Member(ast, 7, PUBLIC, 0);
            case TokenTypes.CLASS_DEF:
            case TokenTypes.INTERFACE_DEF:
            case TokenTypes.ENUM_DEF:
            case TokenTypes.ANNOTATION_DEF:
            case TokenTypes.RECORD_DEF:
                return new Member(ast, 8, visibility(ast, owner), 0);
            default:
                return null;
            }
        }

        private static Member fromField(DetailAST ast, DetailAST owner) {
            boolean enumConstant = ast.getType() == TokenTypes.ENUM_CONSTANT_DEF;
            boolean isStatic = enumConstant || hasModifier(ast, TokenTypes.LITERAL_STATIC);
            boolean isFinal = enumConstant || hasModifier(ast, TokenTypes.FINAL);
            int rank = 2;
            if (isStatic) {
                rank = isFinal ? 0 : 1;
            }
            return new Member(ast, rank, enumConstant ? PUBLIC : visibility(ast, owner), 0);
        }

        private static Member fromMethod(DetailAST ast, DetailAST owner) {
            if (hasAnnotation(ast, "PostConstruct") || hasAnnotation(ast, "PreDestroy")) {
                return new Member(ast, 6, visibility(ast, owner), 0);
            }
            return new Member(ast, 7, visibility(ast, owner), isAccessor(ast) ? 1 : 0);
        }

        private static int visibility(DetailAST member, DetailAST owner) {
            DetailAST modifiers = member.findFirstToken(TokenTypes.MODIFIERS);
            if (modifiers == null) {
                return isInterface(owner) ? PUBLIC : PACKAGE;
            }
            if (modifiers.findFirstToken(TokenTypes.LITERAL_PUBLIC) != null) {
                return PUBLIC;
            }
            if (modifiers.findFirstToken(TokenTypes.LITERAL_PROTECTED) != null) {
                return PROTECTED;
            }
            if (modifiers.findFirstToken(TokenTypes.LITERAL_PRIVATE) != null) {
                return PRIVATE;
            }
            return isInterface(owner) ? PUBLIC : PACKAGE;
        }

        private static boolean isInterface(DetailAST owner) {
            return owner.getType() == TokenTypes.INTERFACE_DEF || owner.getType() == TokenTypes.ANNOTATION_DEF;
        }

        private static boolean hasModifier(DetailAST member, int token) {
            DetailAST modifiers = member.findFirstToken(TokenTypes.MODIFIERS);
            return modifiers != null && modifiers.findFirstToken(token) != null;
        }

        private static boolean hasAnnotation(DetailAST member, String simpleName) {
            DetailAST modifiers = member.findFirstToken(TokenTypes.MODIFIERS);
            if (modifiers == null) {
                return false;
            }
            for (DetailAST annotation = modifiers.findFirstToken(
                    TokenTypes.ANNOTATION); annotation != null; annotation = annotation.getNextSibling()) {
                if (annotation.getType() == TokenTypes.ANNOTATION && containsIdentifier(annotation, simpleName)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsIdentifier(DetailAST ast, String value) {
            if (ast.getType() == TokenTypes.IDENT && value.equals(ast.getText())) {
                return true;
            }
            for (DetailAST child = ast.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (containsIdentifier(child, value)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isAccessor(DetailAST method) {
            DetailAST nameNode = method.findFirstToken(TokenTypes.IDENT);
            if (nameNode == null) {
                return false;
            }
            String name = nameNode.getText();
            DetailAST parameters = method.findFirstToken(TokenTypes.PARAMETERS);
            int parameterCount = parameters == null ? 0 : parameters.getChildCount(TokenTypes.PARAMETER_DEF);
            DetailAST returnType = method.findFirstToken(TokenTypes.TYPE);
            boolean isVoid = returnType != null && returnType.findFirstToken(TokenTypes.LITERAL_VOID) != null;
            if (parameterCount == 0 && !isVoid) {
                return (name.startsWith("get") && hasUppercaseSuffix(name, 3))
                        || (name.startsWith("is") && hasUppercaseSuffix(name, 2));
            }
            return parameterCount == 1 && isVoid && name.startsWith("set") && hasUppercaseSuffix(name, 3);
        }

        private static boolean hasUppercaseSuffix(String name, int prefixLength) {
            return name.length() > prefixLength && Character.isUpperCase(name.charAt(prefixLength));
        }

        private DetailAST ast() {
            return ast;
        }

        private int rank() {
            return rank;
        }

        private int visibility() {
            return visibility;
        }

        private int accessorOrder() {
            return accessorOrder;
        }
    }
}

package org.rpgleparser.tokens;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.WritableToken;
import org.rpgleparser.RpgLexer;
import org.rpgleparser.utils.FixedWidthBufferedReader;

public class PreprocessTokenSource extends TransformTokenSource {

    public static interface CopyBookProvider {
        String lookup(List<Token> copy);
    }

    /**
     * Default implementation of the provider which returns the file in a given
     * folder. Note: the file must have the .copy extension.
     * 
     * @author Ryan
     *
     */
    public static class FileFolderCopyBookProvider implements CopyBookProvider {

        File sourceFolder;

        public FileFolderCopyBookProvider(final File sourceFolder) {
            super();
            if (sourceFolder.isDirectory()) {
                this.sourceFolder = sourceFolder;
            } else {
                this.sourceFolder = sourceFolder.getParentFile();
            }
        }

        @Override
        public String lookup(final List<Token> copy) {
            final String sourceMember = copy.get(copy.size() - 1).getText();
            try {
                final File sourceFile = new File(sourceFolder.getPath() + File.separator + sourceMember + ".copy");
                return loadFile(sourceFile);
            } catch (final Exception e) {
              //  e.printStackTrace();
            }
            return null;
        }
    }

    public static String loadFile(final File file) throws IOException {
        final InputStream is = new FileInputStream(file);
        final byte[] b = new byte[is.available()];
        is.read(b);
        is.close();
        return new String(b);
    }

    protected CopyBookProvider copyBookProvider = null;

    List<Integer> CHECKED_TOKENS = Arrays.asList(new Integer[] { RpgLexer.DIR_IF, RpgLexer.DIR_ELSEIF,
            RpgLexer.DIR_ELSE, RpgLexer.DIR_ENDIF, RpgLexer.DIR_DEFINE, RpgLexer.DIR_COPY });

    List<Integer> IF_TOKENS = Arrays.asList(new Integer[] { RpgLexer.EOF, RpgLexer.EOL, RpgLexer.DIR_NOT,
            RpgLexer.DIR_DEFINE, RpgLexer.DIR_OtherText });

    List<Integer> OTHERTEXT_TOKENS = Arrays
            .asList(new Integer[] { RpgLexer.EOF, RpgLexer.EOL, RpgLexer.DIR_OtherText });
    List<Integer> EOL_TOKENS = Arrays.asList(new Integer[] { RpgLexer.EOF, RpgLexer.EOL });
    List<Integer> EOL_OR_TEXT_TOKENS = Arrays
            .asList(new Integer[] { RpgLexer.EOF, RpgLexer.EOL, RpgLexer.DIR_OtherText, RpgLexer.StringContent });
    Set<String> defined = new HashSet<>();

    Stack<String> directives = new Stack<>();
    boolean ifCondition = false;
    boolean ifConditionWasMatched = false;

    public PreprocessTokenSource(final TokenSource tokenSource) {
        this(tokenSource, (CopyBookProvider) null);
    }

    public PreprocessTokenSource(final TokenSource tokenSource, final CopyBookProvider copyBookProvider) {
        super(tokenSource);
        this.copyBookProvider = copyBookProvider;
    }

    public PreprocessTokenSource(final TokenSource tokenSource, final Lexer inclLexer) {
        super(tokenSource, inclLexer);
    }

    private Token consumeAndHideUntil(final List<Integer> stopAt) {
        Token currentToken = null;
        while (!stopAt.contains((currentToken = getTokenSource().nextToken()).getType())) {
            hideAndAdd(currentToken);
        }
        return currentToken;
    }

    private void consumeCopyDirective(final Token currentToken) {
        Token otherTextToken = consumeAndHideUntil(EOL_OR_TEXT_TOKENS);
        final List<Token> copyTokens = new ArrayList<>();
        while ((otherTextToken != null) && !EOL_TOKENS.contains(otherTextToken.getType())) {
            hideAndAdd(otherTextToken);
            copyTokens.add(otherTextToken);
            otherTextToken = consumeAndHideUntil(EOL_OR_TEXT_TOKENS);
        }
        if (copyTokens.size() > 0) {
            try {
                final String inputString = copyBookProvider != null ? copyBookProvider.lookup(copyTokens) : null;
                if (inputString != null) {
                    final ANTLRInputStream input = new ANTLRInputStream(new FixedWidthBufferedReader(inputString));
                    final RpgLexer rpglexer = new RpgLexer(input);
                    tokenQueue.addAll(rpglexer.getAllTokens());
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void consumeDefineDirective(Token currentToken) {
        if (currentToken.getType() == RpgLexer.DIR_DEFINE) {
            currentToken = getTokenSource().nextToken();
            if (currentToken.getType() == RpgLexer.DIR_OtherText) {
                defined.add(currentToken.getText());
                hideAndAdd(currentToken);
            }
            consumeAndHideUntil(EOL_TOKENS);
        }
    }

    private void consumeElseDirective(final Token currentToken) {
        ifCondition = !ifConditionWasMatched;
        consumeAndHideUntil(EOL_TOKENS);
    }

    private void consumeEndIfDirective(final Token currentToken) {
        directives.pop();
        consumeAndHideUntil(EOL_TOKENS);
        ifCondition = false;
    }

    private void consumeIfDirective(Token currentToken) {
        boolean negate = false;
        ifCondition = false;
        ifConditionWasMatched = false;
        if (currentToken.getType() == RpgLexer.DIR_IF) {
            directives.push("IF");
        }
        currentToken = consumeAndHideUntil(IF_TOKENS);
        if (currentToken.getType() == RpgLexer.DIR_NOT) {
            hideAndAdd(currentToken);
            negate = true;
        }
        currentToken = getTokenSource().nextToken();
        hideAndAdd(currentToken);
        if (currentToken.getType() == RpgLexer.DIR_DEFINED) {
            currentToken = consumeAndHideUntil(OTHERTEXT_TOKENS);
            if (!ifConditionWasMatched && (currentToken.getType() == RpgLexer.DIR_OtherText)) {
                ifCondition = defined.contains(currentToken.getText());
                if (negate) {
                    ifCondition = !ifCondition;
                }
            }
        }
        ifConditionWasMatched = ifConditionWasMatched || ifCondition;
        consumeAndHideUntil(EOL_TOKENS);
    }

    private void consumeUnDefineDirective(Token currentToken) {
        if (currentToken.getType() == RpgLexer.DIR_UNDEFINE) {
            currentToken = getTokenSource().nextToken();
            if (currentToken.getType() == RpgLexer.DIR_OtherText) {
                defined.remove(currentToken.getText());
                hideAndAdd(currentToken);
            }
            consumeAndHideUntil(EOL_TOKENS);
        }
    }

    private void hide(final Token t) {
        if (t instanceof WritableToken) {
            ((WritableToken) t).setChannel(Lexer.HIDDEN);
        }

    }

    private void hideAndAdd(final Token t) {
        if (t instanceof WritableToken) {
            ((WritableToken) t).setChannel(Lexer.HIDDEN);
        }
        addToken(t);
    }

    /*
     * This method can change the token, Replace it, or remove it from the
     * stream (return null)
     */
    @Override
    public Token queryToken(final Token nextToken) {

        if ((directives.size() > 0) && !ifCondition) {
            hide(nextToken);
        }
        if (nextToken.getType() != RpgLexer.DIRECTIVE) {
            return nextToken;
        }
        final Token currentToken = getTokenSource().nextToken();
        if ((directives.size() > 0) && !ifCondition) {
            hide(currentToken);
        }
        if (!CHECKED_TOKENS.contains(currentToken.getType())) {
            // hide tokens that are in an IF directive that is not true
            addToken(currentToken);
            return nextToken;
        }
        hide(nextToken);
        hideAndAdd(currentToken);
        if ((currentToken.getType() == RpgLexer.DIR_IF) || (currentToken.getType() == RpgLexer.DIR_ELSEIF)) {
            consumeIfDirective(currentToken);
        } else if (currentToken.getType() == RpgLexer.DIR_ELSE) {
            consumeElseDirective(currentToken);
        } else if (currentToken.getType() == RpgLexer.DIR_ENDIF) {
            consumeEndIfDirective(currentToken);
        } else if (currentToken.getType() == RpgLexer.DIR_DEFINE) {
            consumeDefineDirective(currentToken);
        } else if (currentToken.getType() == RpgLexer.DIR_UNDEFINE) {
            consumeUnDefineDirective(currentToken);
        } else if (currentToken.getType() == RpgLexer.DIR_COPY) {
            consumeCopyDirective(currentToken);
        }
        return nextToken;
    }
}

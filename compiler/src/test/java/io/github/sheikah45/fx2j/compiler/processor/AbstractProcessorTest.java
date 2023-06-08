package io.github.sheikah45.fx2j.compiler.processor;

import io.github.sheikah45.fx2j.api.Fx2jBuilder;
import io.github.sheikah45.fx2j.compiler.testutils.TestCompiler;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Execution(ExecutionMode.CONCURRENT)
public abstract class AbstractProcessorTest {


    protected static final String ROOT_PACKAGE = "test";
    protected final Path resourcesRoot = Path.of("src/test/resources");
    protected final ClassLoader classLoader = getClass().getClassLoader();

    @TempDir
    private Path compileOutput;

    @BeforeAll
    public static void setup() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {}
    }

    protected <C, R> Fx2jBuilder<C, R> compileAndLoadBuilder(FxmlProcessor mainFxmlProcessor,
                                                             FxmlProcessor... supportingFxmlProcessors)
            throws Exception {
        List<FxmlProcessor> compilationUnits = Stream.concat(Stream.of(mainFxmlProcessor),
                                                             Arrays.stream(supportingFxmlProcessors)).toList();
        Fx2jProcessor fx2jProcessor = new Fx2jProcessor(compilationUnits, false);
        String canonicalClassName = mainFxmlProcessor.getCanonicalClassName();
        Class<Fx2jBuilder<C, R>> fx2jBuilderClass = TestCompiler.compileAndLoadClass(compileOutput, fx2jProcessor,
                                                                                     canonicalClassName);
        return fx2jBuilderClass.getConstructor().newInstance();
    }

}

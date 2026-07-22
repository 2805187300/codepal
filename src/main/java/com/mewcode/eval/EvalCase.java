package com.mewcode.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String description,
        String prompt,
        List<String> expectedFileEdits,
        List<String> requiredOutputKeywords,
        String successBashCheck
) {

    public static List<EvalCase> sample() {
        return List.of(
                new EvalCase(
                        "hello-001",
                        "Write hello world to a file",
                        "Write a hello world Java program to /tmp/Hello.java",
                        List.of("/tmp/Hello.java"),
                        List.of("Hello", "main"),
                        "javac /tmp/Hello.java"
                ),
                new EvalCase(
                        "edit-001",
                        "Edit a specific string",
                        "In /tmp/test.txt replace 'foo' with 'bar'",
                        List.of("/tmp/test.txt"),
                        List.of(),
                        null
                ),
                new EvalCase(
                        "bash-001",
                        "Run a bash command",
                        "List the files in /tmp and count them",
                        List.of(),
                        List.of(),
                        null
                )
        );
    }

    public static class Loader {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        public static List<EvalCase> loadFromDir(String dirPath) {
            List<EvalCase> cases = new ArrayList<>();
            Path dir = Paths.get(dirPath);

            if (!Files.isDirectory(dir)) {
                System.err.println("EvalCase.Loader: not a directory: " + dirPath);
                return cases;
            }

            try (Stream<Path> paths = Files.list(dir)) {
                paths
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted()
                        .forEach(p -> {
                            try {
                                String json = Files.readString(p);
                                EvalCase evalCase = MAPPER.readValue(json, EvalCase.class);
                                cases.add(evalCase);
                            } catch (IOException e) {
                                System.err.println("EvalCase.Loader: failed to parse " + p + ": " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                System.err.println("EvalCase.Loader: failed to list directory " + dirPath + ": " + e.getMessage());
            }

            return cases;
        }

        public static EvalCase fromJson(String json) throws IOException {
            return MAPPER.readValue(json, EvalCase.class);
        }
    }
}

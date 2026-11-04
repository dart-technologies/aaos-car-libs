/* Copyright (C) 2026 Dart Technologies. Licensed under the Apache License, Version 2.0. */
package art.dart.a2ui.harness;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import art.dart.a2ui.demo.UseCaseCorpus;
import art.dart.a2ui.model.ComponentType;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Exports the corpus ids and intents for scripts/compare-providers.py, so the cases are read from
 * the Java builders rather than copied. Run only by {@code :a2ui-model:exportUseCaseCorpus}.
 */
public class UseCaseCorpusExport {
    @Test
    public void export() throws IOException {
        String path = System.getProperty("a2ui.corpusExport");
        assumeTrue("set by :a2ui-model:exportUseCaseCorpus", path != null);
        StringBuilder out = new StringBuilder("{\"version\":")
                .append(HarnessJson.quote(UseCaseCorpus.VERSION))
                .append(",\"catalogId\":").append(HarnessJson.quote(UseCaseCorpus.CATALOG_ID))
                .append(",\"cases\":[");
        boolean first = true;
        for (UseCaseCorpus.UseCase useCase : UseCaseCorpus.all()) {
            out.append(first ? "" : ",").append("\n{\"id\":")
                    .append(HarnessJson.quote(useCase.getId()))
                    .append(",\"intent\":").append(HarnessJson.quote(useCase.getIntent()))
                    .append(",\"interestingAt\":")
                    .append(HarnessJson.quote(useCase.getInterestingAt()))
                    .append(",\"componentTypes\":[");
            boolean firstType = true;
            for (ComponentType type : useCase.getComponentTypes()) {
                out.append(firstType ? "" : ",").append(HarnessJson.quote(type.catalogName()));
                firstType = false;
            }
            out.append("]}");
            first = false;
        }
        out.append("]}\n");
        File file = new File(path);
        File parent = file.getAbsoluteFile().getParentFile();
        assertThat(parent.isDirectory() || parent.mkdirs()).isTrue();
        Files.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(UseCaseCorpus.all()).isNotEmpty();
    }
}

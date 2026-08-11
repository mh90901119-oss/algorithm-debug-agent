package org.example.algorithmdebug.adapter.waferdemo;

import org.example.algorithmdebug.adapter.AdapterException;
import org.example.algorithmdebug.adapter.InputLocator;
import org.example.algorithmdebug.adapter.ProjectDescriptor;
import org.example.algorithmdebug.contracts.TargetTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 根据已验证 Case Catalog 定位 Wafer Demo 输入 JSON。 */
public final class WaferInputLocator implements InputLocator {

    @Override
    public Optional<Path> locate(ProjectDescriptor project, TargetTest targetTest)
            throws AdapterException {
        WaferDemoChecks.requireWaferDemoProject(project);
        Path input = project.projectRoot()
                .resolve(WaferDemoCaseCatalog.requireCase(targetTest).inputRelativePath())
                .normalize();
        if (!input.startsWith(project.projectRoot()) || !Files.isRegularFile(input)) {
            throw new AdapterException(
                    "ADAPTER_INPUT_NOT_FOUND",
                    "Wafer Demo 输入不存在: " + input);
        }
        return Optional.of(input);
    }
}


package com.jar.flowcapture.config;

import com.jar.flowcapture.codegen.FileWriterService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails fast at boot if flow-capture.target-repo-path is wrong, instead of letting
 * a QA engineer record an entire flow only to lose it when finish() can't write
 * anywhere (FileWriterService used to only check this at write time).
 */
@Component
public class StartupValidation implements ApplicationRunner {

    private final FileWriterService fileWriterService;

    public StartupValidation(FileWriterService fileWriterService) {
        this.fileWriterService = fileWriterService;
    }

    @Override
    public void run(ApplicationArguments args) {
        fileWriterService.validateTargetRepoPathOrThrow();
    }
}

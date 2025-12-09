package com.capability.pdfgeneration.service.config;

import lombok.extern.slf4j.Slf4j;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.Arrays;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "office.enabled", havingValue = "true", matchIfMissing = true)
public class OfficeConfig {

    @Value("${office.home:}") // explicit LO path if you have it installed
    private String officeHomeProp;

    // NOTE: Finder complains about dot folders; keep non-hidden by default.
    @Value("${office.install.workingDir:#{systemProperties['user.home']}/jod-lo}")
    private String workingDirProp;

    @Value("${office.portNumbers:2002}")
    private String portNumbers;

    @Value("${office.disableOpenGL:true}")
    private boolean disableOpenGL;

    @Value("${office.processTimeout:300000}")
    private long processTimeout;

    @Value("${office.taskExecutionTimeout:120000}")
    private long taskExecutionTimeout;

    @Value("${office.taskQueueTimeout:120000}")
    private long taskQueueTimeout;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public OfficeManager officeManager() {
        final String officeHome = resolveOfficeHome();

        final File workingDir = new File(workingDirProp);
        ensureDir(workingDir);

        log.info("JODConverter: workingDir={}", workingDir.getAbsolutePath());
        if (officeHome != null && !officeHome.isBlank()) {
            log.info("JODConverter: using installed LibreOffice at officeHome={}", officeHome);
        } else {
            log.info("JODConverter: no officeHome provided -> will auto-install LibreOffice into workingDir");
        }

        LocalOfficeManager.Builder b = LocalOfficeManager.builder()
                .disableOpengl(disableOpenGL)
                .processTimeout(processTimeout)
                .taskExecutionTimeout(taskExecutionTimeout)
                .taskQueueTimeout(taskQueueTimeout)
                .workingDir(workingDir);

        if (portNumbers != null && !portNumbers.isBlank()) {
            int[] ports = Arrays.stream(portNumbers.split(","))
                    .map(String::trim).mapToInt(Integer::parseInt).toArray();
            b.portNumbers(ports);
            log.info("JODConverter: ports={}", Arrays.toString(ports));
        }

        if (officeHome != null && !officeHome.isBlank()) {
            b.officeHome(officeHome);
        } else {
            b.install(); // auto-download & install LO
        }

        return b.build();
    }

    @Bean
    public DocumentConverter documentConverter(OfficeManager officeManager) {
        return LocalConverter.builder().officeManager(officeManager).build();
    }

    private String resolveOfficeHome() {
        if (officeHomeProp != null && !officeHomeProp.isBlank()) return officeHomeProp;
        String env = System.getenv("OFFICE_HOME");
        return (env != null && !env.isBlank()) ? env : null;
    }

    private static void ensureDir(File dir) {
        if (dir.exists() && !dir.isDirectory()) {
            throw new IllegalStateException("workingDir exists but is not a directory: " + dir);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create workingDir: " + dir);
        }
    }
}
package com.schwab.urlshortener.workflow.scenario;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point for the end-to-end scenarios. Active only when
 * {@code app.workflow.cli.scenario} is set:
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.profiles=dev \
 *     -Dspring-boot.run.arguments="--app.workflow.cli.scenario=all"
 * </pre>
 *
 * Runs the scenario(s), writes each report to
 * {@code target/workflow-scenarios/<name>.md}, prints a one-line verdict per
 * scenario, then exits with code 0 if every evidence check passed and 1
 * otherwise. Mode comes from {@code app.workflow.cli.mode} (default
 * DETERMINISTIC_DEMO, which needs no credentials).
 */
@Component
@Slf4j
public class ScenarioCli implements ApplicationRunner {

    private final ScenarioService scenarios;
    private final WorkflowProperties properties;
    private final ConfigurableApplicationContext context;
    private final String mode;
    private final String outputDir;

    public ScenarioCli(ScenarioService scenarios, WorkflowProperties properties, ConfigurableApplicationContext context,
                       @Value("${app.workflow.cli.mode:DETERMINISTIC_DEMO}") String mode,
                       @Value("${app.workflow.cli.output-dir:target/workflow-scenarios}") String outputDir) {
        this.scenarios = scenarios;
        this.properties = properties;
        this.context = context;
        this.mode = mode;
        this.outputDir = outputDir;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String requested = properties.cli().scenario();
        if (requested == null || requested.isBlank()) {
            return;
        }
        List<String> names = requested.equalsIgnoreCase("all") ? ScenarioService.NAMES : List.of(requested.split(","));
        ExecutionMode executionMode = ExecutionMode.valueOf(mode.trim().toUpperCase());
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);
        boolean allPassed = true;
        List<String> verdicts = new ArrayList<>();
        for (String name : names) {
            ScenarioService.Report report = scenarios.run(name.trim(), executionMode);
            Path file = dir.resolve(report.scenario() + ".md");
            Files.writeString(file, report.markdown(), StandardCharsets.UTF_8);
            long passed = report.checks().stream().filter(ScenarioService.Check::passed).count();
            verdicts.add(String.format("SCENARIO %-10s run=%d mode=%s status=%s checks=%d/%d %s -> %s", report.scenario(), report.runId(),
                    report.mode(), report.finalStatus(), passed, report.checks().size(), report.allChecksPassed() ? "PASS" : "FAIL",
                    file.toAbsolutePath()));
            allPassed &= report.allChecksPassed();
        }
        System.out.println();
        System.out.println("==================== workflow scenarios ====================");
        verdicts.forEach(System.out::println);
        System.out.println("=============================================================");
        int code = allPassed ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}

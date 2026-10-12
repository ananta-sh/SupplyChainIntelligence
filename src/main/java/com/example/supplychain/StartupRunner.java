package com.example.supplychain;

import com.example.supplychain.data.DataPipeline;
import com.example.supplychain.data.RawQualityService;
import com.example.supplychain.ml.DelayModelService;
import com.example.supplychain.assistant.AssistantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class StartupRunner implements ApplicationRunner {
    private final RawQualityService quality;
    private final DataPipeline pipeline;
    private final DelayModelService model;
    private final AssistantService assistant;
    private final ConfigurableApplicationContext context;
    private final ObjectMapper mapper;
    @Value("${app.mode:serve}") private String mode;
    @Value("${app.quality-report-path:./artifacts/data-quality-report.json}") private String reportPath;

    public StartupRunner(RawQualityService quality, DataPipeline pipeline, DelayModelService model,
                         AssistantService assistant, ConfigurableApplicationContext context, ObjectMapper mapper) {
        this.quality = quality; this.pipeline = pipeline; this.model = model;
        this.assistant = assistant; this.context = context; this.mapper = mapper;
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        if (mode.equalsIgnoreCase("dq")) {
            writeReport();
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(quality.run()));
            close();
        } else if (mode.equalsIgnoreCase("train")) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(model.train()));
            close();
        } else {
            writeReport();
            pipeline.rebuild();
            model.load();
            if (mode.equalsIgnoreCase("assistant")) {
                String question = args.containsOption("assistant.question") ? args.getOptionValues("assistant.question").get(0) : "";
                System.out.println(assistant.answer(question));
                close();
            }
        }
    }

    private void writeReport() throws Exception {
        Path output = Path.of(reportPath);
        Files.createDirectories(output.toAbsolutePath().getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), quality.run());
    }

    private void close() { context.close(); }
}

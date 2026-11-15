package dev.disputeagent.investigator.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.disputeagent.investigator")
public class InvestigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvestigatorApplication.class, args);
    }
}

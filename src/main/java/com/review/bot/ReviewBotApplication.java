package com.review.bot;

import com.review.bot.cli.ReviewCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ReviewBotApplication {

    /** `review ...` / `request ...` runs the CLI (see ReviewCli); anything else starts the server. */
    public static void main(String[] args) {
        if (ReviewCli.handles(args)) {
            // UTF-8 whatever the console locale, so the verdict icons print (files are always UTF-8)
            PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
            System.exit(ReviewCli.run(args, out, err));
        }
        SpringApplication.run(ReviewBotApplication.class, args);
    }
}

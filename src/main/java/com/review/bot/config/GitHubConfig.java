package com.review.bot.config;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class GitHubConfig {

    @Bean
    public GitHub gitHub(ReviewProperties props) throws IOException {
        return new GitHubBuilder().withOAuthToken(props.githubToken()).build();
    }
}

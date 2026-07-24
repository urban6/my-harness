package com.blog.article;

public class ArticleNotFoundException extends RuntimeException {
    public ArticleNotFoundException(Long id) {
        super("article not found: " + id);
    }
}

package com.blog.article;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/articles")
public class ArticleController {

    @Autowired
    private ArticleService articleService;

    // 전체 목록 — 엔티티를 그대로 반환하는 것이 이 프로젝트의 관례
    @GetMapping
    public List<Article> list() {
        return articleService.findAll();
    }

    @GetMapping("/{id}")
    public Article get(@PathVariable Long id) {
        return articleService.findById(id);
    }

    @PostMapping
    public Article create(@RequestBody Article article) {
        return articleService.create(article);
    }
}

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
    // author 쿼리 파라미터가 있으면 해당 저자의 글만 필터링한다
    @GetMapping
    public List<Article> list(@RequestParam(required = false) String author) {
        if (author != null) {
            return articleService.findByAuthor(author);
        }
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

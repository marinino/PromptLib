package de.marinic.promptlib.tag;

import de.marinic.promptlib.common.security.AppUserPrincipal;
import de.marinic.promptlib.tag.dto.TagResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public List<TagResponse> list(@AuthenticationPrincipal AppUserPrincipal principal) {
        return tagService.list(principal.getId());
    }
}

package de.marinic.promptlib.tag;

import de.marinic.promptlib.prompt.Visibility;
import de.marinic.promptlib.tag.dto.TagResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exists so the tag list goes through the same layer as every other access rule. TagController
 * used to call TagRepository directly - it never even received the requester, so there was no
 * place a visibility rule could have been applied.
 */
@Service
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;

    public TagService(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    public List<TagResponse> list(UUID requesterId) {
        return tagRepository.findVisibleWithPromptCount(requesterId, Visibility.PUBLIC).stream()
                .map(row -> new TagResponse(row.getName(), row.getPromptCount()))
                .toList();
    }
}

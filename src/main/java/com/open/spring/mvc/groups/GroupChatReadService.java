package com.open.spring.mvc.groups;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Unread counts for group chat, the group-side equivalent of the direct-message
 * unread summary.
 *
 * Group messages are not rows: they live in a JSONL file per group in object storage,
 * so there is no query to count them with. A count means reading that group's messages
 * and comparing each one's timestamp against when the member last read the group, which
 * is why {@link #unreadByGroup} takes the groups to look at rather than scanning every
 * group on the site.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatReadService {

    private final GroupChatReadStateJpaRepository readStateRepository;
    private final GroupsJpaRepository groupsRepository;
    private final GroupChatService groupChatService;

    /**
     * Unread count per group id, across this person's groups. A group with nothing
     * unread is left out rather than reported as zero, so the caller can treat the map
     * as "the groups wanting attention".
     *
     * A member who has never opened a group has read nothing, so every message in it
     * counts. Your own messages never count: posting is not something to be notified
     * about.
     *
     * The groups are looked up here rather than taken from {@code person.getGroups()}:
     * that association is lazy and the app runs with open-in-view off, so reading it
     * from a controller throws once the request's session has closed.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> unreadByGroup(Person person) {
        if (person == null || person.getId() == null) {
            return Map.of();
        }
        List<Groups> groups = groupsRepository.findGroupsByPersonId(person.getId());
        if (groups == null || groups.isEmpty()) {
            return Map.of();
        }

        Map<Long, Instant> lastReadByGroup = new HashMap<>();
        for (GroupChatReadState state : readStateRepository.findByPerson(person)) {
            if (state.getGroup() != null && state.getLastReadAt() != null) {
                lastReadByGroup.put(state.getGroup().getId(), state.getLastReadAt());
            }
        }

        String displayName = person.getName();
        Map<Long, Long> unread = new HashMap<>();
        for (Groups group : groups) {
            Instant lastRead = lastReadByGroup.get(group.getId());
            long count = groupChatService.getMessages(group.getName()).stream()
                    .filter(message -> !isFrom(message, displayName))
                    .filter(message -> isAfter(message.getDate(), lastRead))
                    .count();
            if (count > 0) {
                unread.put(group.getId(), count);
            }
        }
        return unread;
    }

    /** Marks the whole group read for this person, as of now. */
    @Transactional
    public void markRead(Groups group, Person person) {
        Optional<GroupChatReadState> existing = readStateRepository.findByGroupAndPerson(group, person);
        GroupChatReadState state = existing.orElseGet(
                () -> new GroupChatReadState(group, person, null));
        state.setLastReadAt(Instant.now());
        readStateRepository.save(state);
    }

    private boolean isFrom(GroupChatMessage message, String displayName) {
        return displayName != null && displayName.equals(message.getAuthor());
    }

    /**
     * A message counts as unread when it was sent after the member last read the group.
     * Dates are ISO-8601 instants written by the server, so they parse; anything
     * unparseable is treated as unread rather than silently swallowed, since a message
     * nobody can date is still a message nobody has seen.
     */
    private boolean isAfter(String sentAt, Instant lastRead) {
        if (lastRead == null) {
            return true;
        }
        if (sentAt == null || sentAt.isBlank()) {
            return true;
        }
        try {
            return Instant.parse(sentAt).isAfter(lastRead);
        } catch (Exception e) {
            log.warn("Unparseable group message date '{}'; counting it as unread", sentAt);
            return true;
        }
    }
}

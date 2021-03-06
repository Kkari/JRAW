package net.dean.jraw.models;

import net.dean.jraw.models.attr.Distinguishable;
import org.codehaus.jackson.JsonNode;

public class Message extends Contribution implements Distinguishable {

    /**
     * Instantiates a new Thing
     *
     * @param dataNode The node to parse data from
     */
    public Message(JsonNode dataNode) {
        super(dataNode);
    }

    @JsonInteraction
    public String getAuthor() {
        return data("author");
    }

    @JsonInteraction
    public RenderStringPair getBody() {
        return data("body", RenderStringPair.class);
    }

    /**
     * Gets the full name of the first message's ID
     * @return The first message
     */
    @JsonInteraction
    public String getFirstMessage() {
        return data("first_message_name");
    }

     /**
     * If this message represents a comment, then this method will return the way in which the logged in user voted.
     * If this message represents a private message, then this method will always return null.
     * @return The way in which the logged in user voted
     */
    @JsonInteraction(nullable = true)
    public VoteDirection getVote() {
        // If "was_comment" == false then "likes" will not exist
        if (!isComment()) {
            return null;
        }

        JsonNode likes = getDataNode().get("likes");
        if (likes.isNull()) {
            return VoteDirection.NO_VOTE;
        }

        return likes.getBooleanValue() ? VoteDirection.UPVOTE : VoteDirection.DOWNVOTE;
    }

    /**
     * Gets the title of the link this comment was posted in, or null if this message represents a private message
     * @return The parent link's title
     */
    @JsonInteraction
    public String getLinkTitle() {
        if (!isComment()) {
            return null;
        }

        return data("link_title");
    }

    /**
     * Checks if this message is unread
     * @return If this message is unread
     */
    @JsonInteraction
    public Boolean isRead() {
        return !data("new", Boolean.class);
    }

    /**
     * Gets the fullname of the submission/comment/message that this is a reply to, or null if this is a top-level comment
     * or private message.
     * @return The ID of the message's parent
     */
    @JsonInteraction(nullable = true)
    public String getParentId() {
        return data("parent_id");
    }

    /**
     * Gets the subject of the message
     * @return The subject
     */
    @JsonInteraction
    public String getSubject() {
        return data("subject");
    }

    /**
     * Gets the subreddit this was posted in, or null if this message is not a comment
     * @return The subreddit this was posted in
     */
    @JsonInteraction(nullable = true)
    public String getSubreddit() {
        return data("subreddit");
    }

    /**
     * Checks if this message is a comment
     * @return True if this message is a comment, false if it is a private message
     */
    @JsonInteraction
    public Boolean isComment() {
        return data("was_comment", Boolean.class);
    }

    @Override
    public ThingType getType() {
        return ThingType.MESSAGE;
    }
}

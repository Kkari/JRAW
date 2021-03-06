package net.dean.jraw;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Response;
import net.dean.jraw.http.*;
import net.dean.jraw.managers.AccountManager;
import net.dean.jraw.models.*;
import net.dean.jraw.pagination.Sorting;
import net.dean.jraw.pagination.SubredditPaginator;
import org.codehaus.jackson.JsonNode;

import java.net.HttpCookie;
import java.util.*;

/**
 * This class provides access to the most basic Reddit features such as logging in. It is recommended that only one instance
 * of this class is used at a time, unless you disable request management and implement your own.
 */
public class RedditClient extends RestClient<RedditResponse> {

    /** The host that will be used to execute most HTTP(S) requests */
    public static final String HOST = "www.reddit.com";

    /**
     * The host that will be used to execute OAuth requests, with the exception of authorization, in which case
     * {@link #HOST_HTTPS_SPECIAL} will be used
     */
    public static final String HOST_OAUTH = "oauth.reddit.com";

    /** The host that will be used for logging in, OAuth authorizations, and preferences */
    public static final String HOST_HTTPS_SPECIAL = "ssl.reddit.com";

    /** The name of the header that will be assigned upon a successful login */
    private static final String HEADER_MODHASH = "X-Modhash";

    /** The amount of requests allowed per minute without using OAuth */
    private static final int REQUESTS_PER_MINUTE = 30;

    /** The amount of trending subreddits that will appear in each /r/trendingsubreddits post */
    private static final int NUM_TRENDING_SUBREDDITS = 5;

    private String authenticatedUser;

    /**
     * Instantiates a new RedditClient and adds the given user agent to the default headers of the RestClient
     *
     * @param userAgent The User-Agent header that will be sent with all the HTTP requests.
     *                  <blockquote>Change your client's
     *                  User-Agent string to something unique and descriptive, preferably referencing your reddit
     *                  username. From the <a href="https://github.com/reddit/reddit/wiki/API">Reddit Wiki on Github</a>:
     *                  <ul>
     *                  <li>Many default User-Agents (like "Python/urllib" or "Java") are drastically limited to
     *                  encourage unique and descriptive user-agent strings.</li>
     *                  <li>If you're making an application for others to use, please include a version number in
     *                  the user agent. This allows us to block buggy versions without blocking all versions of
     *                  your app.</li>
     *                  <li>NEVER lie about your user-agent. This includes spoofing popular browsers and spoofing
     *                  other bots. We will ban liars with extreme prejudice.</li>
     *                  </ul>
     *                  </blockquote>
     */
    public RedditClient(String userAgent) {
        super(HOST, userAgent, REQUESTS_PER_MINUTE);
    }

    @Override
    protected RedditResponse initResponse(Response r) {
        return new RedditResponse(r);
    }

    /**
     * Gets the name of the currently logged in user
     * @return The name of the currently logged in user
     */
    public String getAuthenticatedUser() {
        return authenticatedUser;
    }

    /**
     * Logs in to an account and returns the data associated with it
     *
     * @param username The username to log in to
     * @param password The password of the username
     * @return An Account object that has the same username as the username parameter
     * @throws NetworkException If there was a problem sending the request
     * @throws ApiException If the API returned an error (most likely because of an incorrect password)
     */
    @EndpointImplementation(Endpoints.LOGIN)
    public LoggedInAccount login(String username, String password) throws NetworkException, ApiException {
        RestRequest request = request()
                .host(HOST_HTTPS_SPECIAL)
                .https(true) // Always HTTPS
                .endpoint(Endpoints.LOGIN)
                .post(JrawUtils.args(
                        "user", username,
                        "passwd", password,
                        "api_type", "json"
                )).sensitiveArgs("passwd")
                .build();

        RedditResponse loginResponse = execute(request);

        if (loginResponse.hasErrors()) {
            throw loginResponse.getErrors()[0];
        }

        setHttpsDefault(loginResponse.getJson().get("json").get("data").get("need_https").asBoolean());
        setHttpsDefault(true);

        String modhash = loginResponse.getJson().get("json").get("data").get("modhash").getTextValue();

        // Add the X-Modhash header, or update it if it already exists
        defaultHeaders.put(HEADER_MODHASH, modhash);

        LoggedInAccount me = me();
        this.authenticatedUser = me.getFullName();
        return me;
    }

    /**
     * Gets the currently logged in account
     *
     * @return The currently logged in account
     * @throws NetworkException If the user has not been logged in yet
     */
    @EndpointImplementation(Endpoints.ME)
    public LoggedInAccount me() throws NetworkException {
        RedditResponse response = execute(request()
                .endpoint(Endpoints.ME)
                .get()
                .build());
        return new LoggedInAccount(response.getJson().get("data"));
    }

    /**
     * Tests if the user is logged in by checking if a cookie is set called "reddit_session" and its domain is "reddit.com"
     *
     * @return True if the user is logged in
     */
    public boolean isLoggedIn() {
        for (HttpCookie cookie : cookieJar.getCookies()) {
            if (cookie.getName().equals("reddit_session") && cookie.getDomain().equals("reddit.com")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the current user needs a captcha to do specific actions such as submit links and compose private messages.
     * This will always be true if there is no logged in user. Usually, this method will return {@code true} if
     * the current logged in user has more than 10 link karma
     *
     * @return True if the user needs a captcha to do a specific action, else if not or not logged in.
     * @throws NetworkException If there was an issue sending the HTTP request
     */
    @EndpointImplementation(Endpoints.NEEDS_CAPTCHA)
    public boolean needsCaptcha() throws NetworkException {
        try {
            // This endpoint does not return JSON, but rather just "true" or "false"
            RedditResponse response = execute(request()
                    .endpoint(Endpoints.NEEDS_CAPTCHA)
                    .get()
                    .build());
            return Boolean.parseBoolean(response.getRaw());
        } catch (NetworkException e) {
            throw new NetworkException("Unable to make the request to /api/needs_captcha.json", e);
        }
    }

    /**
     * Fetches a new captcha from the API
     *
     * @return A new Captcha
     * @throws NetworkException If there was a problem executing the HTTP request
     * @throws ApiException If the Reddit API returned an error
     */
    @EndpointImplementation(Endpoints.NEW_CAPTCHA)
    public Captcha getNewCaptcha() throws NetworkException, ApiException {
        try {
            RedditResponse response = execute(request()
                    .endpoint(Endpoints.NEW_CAPTCHA)
                    .post(JrawUtils.args(
                            "api_type", "json"
                    )).build());

            // Some strange response you got there, reddit...
            if (response.hasErrors()) {
                throw response.getErrors()[0];
            }
            String id = response.getJson().get("json").get("data").get("iden").asText();

            return getCaptcha(id);
        } catch (NetworkException e) {
            throw new NetworkException("Unable to make the request to /api/new_captcha", e);
        }
    }

    /**
     * Gets a Captcha by its ID
     *
     * @param id The ID of the wanted captcha
     * @return A new Captcha object
     * @throws NetworkException If there was a problem executing the HTTP request
     */
    @EndpointImplementation(Endpoints.CAPTCHA_IDEN)
    public Captcha getCaptcha(String id) throws NetworkException {
        // Use Request to format the URL
        RestRequest request = request()
                .endpoint(Endpoints.CAPTCHA_IDEN, id)
                .get()
                .build();

        return new Captcha(id, request.getUrl());
    }

    /**
     * Gets a user with a specific username
     *
     * @param username The name of the desired user
     * @return An Account whose name matches the given username
     * @throws NetworkException If the user does not exist or there was a problem making the request
     */
    @EndpointImplementation(Endpoints.USER_USERNAME_ABOUT)
    public Account getUser(String username) throws NetworkException {
        return execute(request()
                .endpoint(Endpoints.USER_USERNAME_ABOUT, username)
                .get()
                .build()).as(Account.class);
    }

    /**
     * Gets a link with a specific ID
     *
     * @param id The link's ID, ex: "92dd8"
     * @return A new Link object
     * @throws NetworkException If the link does not exist or there was a problem making the request
     */
    public Submission getSubmission(String id) throws NetworkException {
        return getSubmission(new SubmissionRequest(id));
    }

    @EndpointImplementation(Endpoints.COMMENTS_ARTICLE)
    public Submission getSubmission(SubmissionRequest request) throws NetworkException {
        Map<String, String> args = new HashMap<>();
        if (request.depth.isPresent())
            args.put("depth", Integer.toString(request.depth.get()));
        if (request.context.isPresent())
            args.put("context", Integer.toString(request.context.get()));
        if (request.limit.isPresent())
            args.put("limit", Integer.toString(request.limit.get()));
        if (request.focus.isPresent() && !JrawUtils.isFullName(request.focus.get()))
            args.put("comment", request.focus.get());
        if (request.sort.isPresent())
            args.put("sort", request.sort.get().name().toLowerCase());

        return execute(request()
                .path(String.format("/comments/%s.json", request.id))
                .query(args)
                .build()).as(Submission.class);

    }

    /**
     * Gets a Subreddit
     *
     * @param name The subreddit's name
     * @return A new Subreddit object
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.SUBREDDIT_ABOUT)
    public Subreddit getSubreddit(String name) throws NetworkException {
        return execute(request()
                .endpoint(Endpoints.SUBREDDIT_ABOUT, name)
                .build()).as(Subreddit.class);
    }

    /**
     * Checks if a given username is available
     *
     * @param name The username to test
     * @return True if that username is available for registration, false if else
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.USERNAME_AVAILABLE)
    public boolean isUsernameAvailable(String name) throws NetworkException {
        RedditResponse response = execute(request()
                .endpoint(Endpoints.USERNAME_AVAILABLE)
                .query("user", name)
                .build());

        return Boolean.parseBoolean(response.getRaw());
    }

    /**
     * Gets a random submission
     * @return A random submission
     * @throws NetworkException If there was a problem executing the request
     */
    public Submission getRandom() throws NetworkException {
        return getRandom(null);
    }

    /**
     * Gets a random submission from a specific subreddit
     * @param subreddit The subreddit to use
     * @return A random submission
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.RANDOM)
    public Submission getRandom(String subreddit) throws NetworkException  {
        String path = JrawUtils.getSubredditPath(subreddit, "/random.json");

        // Favor path() instead of endpoint() because we have already decided the path above
        return execute(request()
                .path(path)
                .build()).as(Submission.class);
    }

    /**
     * Gets a random subreddit
     * @return A random subreddit
     * @throws NetworkException If there was a problem with the request
     */
    public Subreddit getRandomSubreddit() throws NetworkException {
        return getSubreddit("random");
    }

    /**
     * Gets the text displayed in the "submit link" form.
     * @param subreddit The subreddit to use
     * @return The text displayed int he "submit link" form
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.SUBMIT_TEXT)
    public RenderStringPair getSubmitText(String subreddit) throws NetworkException {
        String path = JrawUtils.getSubredditPath(subreddit, "/api/submit_text.json");

        JsonNode node = execute(request()
                .path(path)
                .build()).getJson();
        return new RenderStringPair(node.get("submit_text").asText(), node.get("submit_text_html").asText());
    }

    /**
     * Gets a list of subreddit names by a topic. For example, the topic "programming" returns "programming", "ProgrammerHumor", etc.
     * @param topic The topic to use
     * @return A list of subreddits related to the given topic
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.SUBREDDITS_BY_TOPIC)
    public List<String> getSubredditsByTopic(String topic) throws NetworkException {
        List<String> subreddits = new ArrayList<>();

        RestRequest request = request()
                .endpoint(Endpoints.SUBREDDITS_BY_TOPIC)
                .query("query", topic)
                .build();

        JsonNode node = execute(request).getJson();
        for (JsonNode childNode : node) {
            subreddits.add(childNode.get("name").asText());
        }

        return subreddits;
    }

    /**
     * Gets a list of subreddits that start with the given string. For instance, searching for "fun" would return
     * {@code ["funny", "FunnyandSad", "funnysigns", "funnycharts", ...]}
     * @param start The begging of the subreddit to search for
     * @param includeNsfw Whether to include NSFW subreddits.
     * @return A list of subreddits that starts with the given string
     * @throws NetworkException If there was a problem executing the request
     */
    @EndpointImplementation(Endpoints.SEARCH_REDDIT_NAMES)
    public List<String> searchSubreddits(String start, boolean includeNsfw) throws NetworkException {
        List<String> subs = new ArrayList<>();

        RestRequest request = request()
                .endpoint(Endpoints.SEARCH_REDDIT_NAMES)
                .post(JrawUtils.args(
                        "query", start,
                        "include_over_18", includeNsfw
                )).build();
        JsonNode node = execute(request).getJson();

        for (JsonNode name : node.get("names")) {
            subs.add(name.asText());
        }

        return subs;
    }

    /**
     * Gets the contents of the CSS file affiliated with a given subreddit (or the front page)
     * @param subreddit The subreddit to use, or null for the front page.
     * @return The content of the raw CSS file
     * @throws NetworkException If there was a problem sending the request, or the {@code Content-Type} header's value was
     *                          not {@code text/css}.
     */
    @EndpointImplementation(Endpoints.STYLESHEET)
    public String getStylesheet(String subreddit) throws NetworkException {
        String path = JrawUtils.getSubredditPath(subreddit, "/stylesheet");

        RestRequest r = request()
                .path(path)
                .build();
        RedditResponse response = execute(r);

        MediaType actual = response.getType();
        MediaType expected = MediaTypes.CSS.type();
        if (!JrawUtils.typeComparison(actual, MediaTypes.CSS.type())) {
            throw new NetworkException(String.format("The request did not return a Content-Type of %s/%s (was \"%s/%s\")",
                    expected.type(), expected.subtype(), actual.type(), actual.subtype()));
        }

        return response.getRaw();
    }

    /**
     * Gets a list of trending subreddits' names. See <a href="http://www.reddit.com/r/trendingsubreddits/">here</a> for more.
     * @return A list of trending subreddits' names
     */
    public List<String> getTrendingSubreddits() {
        SubredditPaginator paginator = new SubredditPaginator(this);
        paginator.setSubreddit("trendingsubreddits");
        paginator.setSorting(Sorting.NEW);

        Submission latest = paginator.next().get(0);
        String title = latest.getTitle();
        String[] parts = title.split(" ");
        List<String> subreddits = new ArrayList<>(NUM_TRENDING_SUBREDDITS);

        for (String part : parts) {
            if (part.startsWith("/r/")) {
                String sub = part.substring("/r/".length());
                // All but the last part will be formatted like "/r/{name},", so remove the commas
                sub = sub.replace(",", "");
                subreddits.add(sub);
            }
        }

        return subreddits;
    }

    /**
     * This class is used by {@link #getSubmission(net.dean.jraw.RedditClient.SubmissionRequest)} to specify the
     * parameters of the request.
     */
    public static class SubmissionRequest {
        private final String id;
        private Optional<Integer> depth;
        private Optional<Integer> limit;
        private Optional<Integer> context;
        private Optional<AccountManager.CommentSort> sort;
        private Optional<String> focus;

        /**
         * Instantiates a new SubmissionRequeslt
         * @param id The link's ID, ex: "92dd8"
         */
        public SubmissionRequest(String id) {
            this.id = id;
            this.depth = Optional.empty();
            this.limit = Optional.empty();
            this.context = Optional.empty();
            this.sort = Optional.empty();
            this.focus = Optional.empty();
        }

        /**
         * Sets the maximum amount of subtrees returned by this request. If the number is less than 1, it is ignored by
         * the Reddit API and no depth restriction is enacted.
         * @param depth The depth
         * @return This SubmissionRequest
         */
        public SubmissionRequest depth(Integer depth) {
            if (depth != null) {
                this.depth = Optional.of(depth);
            }
            return this;
        }

        /**
         * Sets the maximum amount of comments to return
         * @param limit The limit
         * @return This SubmissionRequest
         */
        public SubmissionRequest limit(Integer limit) {
            if (limit != null) {
                this.limit = Optional.of(limit);
            }
            return this;
        }

        /**
         * Sets the number of parents shown in relation to the focused comment. For example, if the focused comment is
         * in the eighth level of the comment tree (meaning there are seven replies above it), and the context is set to
         * six, then the response will also contain the six direct parents of the given comment. For a better understanding,
         * play with <a href="https://www.reddit.com/comments/92dd8?comment=c0b73aj&context=8>this link</a>.
         *
         * @param context The number of parent comments to return in relation to the focused comment.
         * @return This SubmissionRequest
         */
        public SubmissionRequest context(Integer context) {
            if (context != null) {
                this.context = Optional.of(context);
            }
            return this;
        }

        /**
         * Sets the sorting for the comments in the response
         * @param sort The sorting
         * @return This SubmissionRequest
         */
        public SubmissionRequest sort(AccountManager.CommentSort sort) {
            if (sort != null) {
                this.sort = Optional.of(sort);
            }
            return this;
        }

        /**
         * Sets the ID of the comment to focus on. If this comment does not exist, then this parameter is ignored.
         * Otherwise, only one comment tree is returned: the one in which the given comment resides.
         *
         * @param focus The ID of the comment to focus on. For example: "c0b6xx0".
         * @return This SubmissionRequest
         */
        public SubmissionRequest focus(String focus) {
            if (focus != null) {
                this.focus = Optional.of(focus);
            }
            return this;
        }
    }

}

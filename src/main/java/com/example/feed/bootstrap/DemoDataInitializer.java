package com.example.feed.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "feed.demo-data", name = "enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private static final String DEMO_PASSWORD = "demo12345";
    private static final String SEED_MARKER_POST = "d1000000-0000-0000-0000-000000000001";

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;

    public DemoDataInitializer(JdbcClient jdbc, PasswordEncoder passwordEncoder,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> initialize());
    }

    private void initialize() {
        if (jdbc.sql("SELECT COUNT(*) FROM posts WHERE id = :id")
                .param("id", SEED_MARKER_POST).query(Long.class).single() > 0) {
            log.info("Demo data already exists; initialization skipped");
            return;
        }

        String passwordHash = passwordEncoder.encode(DEMO_PASSWORD);
        long alice = user("demo_alice", "Alice（管理员）", "喜欢摄影、骑行和记录生活。", "ADMIN", passwordHash);
        long bob = user("demo_bob", "Bob", "周末骑行爱好者，正在收集城市路线。", "USER", passwordHash);
        long carol = user("demo_carol", "Carol", "烘焙新手，也喜欢分享书和电影。", "USER", passwordHash);
        long dave = user("demo_dave", "Dave", "后端工程师，咖啡浓度决定代码质量。", "USER", passwordHash);
        long erin = user("demo_erin", "Erin", "阳台种植和猫咪观察员。", "USER", passwordHash);
        long frank = user("demo_frank", "Frank", "正在探索这个新的朋友圈。", "USER", passwordHash);
        long george = user("demo_george", "George", "用于联调拉黑场景的演示用户。", "USER", passwordHash);

        friendship(alice, bob);
        friendship(alice, carol);
        friendship(alice, erin);
        friendship(bob, dave);
        friendship(carol, erin);
        jdbc.sql("INSERT INTO blocks(blocker_id, blocked_id) VALUES (:blocker, :blocked)")
                .param("blocker", alice).param("blocked", george).update();

        long incomingRequest = friendRequest(dave, alice, "PENDING", null);
        friendRequest(alice, frank, "PENDING", null);

        Instant now = Instant.now();
        String bobPost = SEED_MARKER_POST;
        String carolPost = "d1000000-0000-0000-0000-000000000002";
        String erinPost = "d1000000-0000-0000-0000-000000000003";
        String privatePost = "d1000000-0000-0000-0000-000000000004";
        String alicePost = "d1000000-0000-0000-0000-000000000005";

        post(bobPost, bob, "e1000000-0000-0000-0000-000000000001", "a", "周末骑行路线踩点完成，风比预想中温柔。",
                "ALL_FRIENDS", now.minusSeconds(5 * 3600));
        post(carolPost, carol, "e1000000-0000-0000-0000-000000000002", "b", "新烤的司康出炉，给 Alice 留了一块。",
                "INCLUDE_LIST", now.minusSeconds(4 * 3600));
        post(erinPost, erin, "e1000000-0000-0000-0000-000000000003", "c", "今天把阳台的小番茄换盆了，期待第一颗果实。",
                "EXCLUDE_LIST", now.minusSeconds(3 * 3600));
        post(privatePost, alice, "e1000000-0000-0000-0000-000000000004", "d", "下周要完成的三件小事：阅读、运动、见朋友。",
                "ONLY_ME", now.minusSeconds(2 * 3600));
        post(alicePost, alice, "e1000000-0000-0000-0000-000000000005", "e", "欢迎来到我们的安静动态圈 👋",
                "ALL_FRIENDS", now.minusSeconds(3600));

        acl(carolPost, alice, "ALLOW");
        acl(erinPost, carol, "DENY");

        inbox(bobPost, bob, bob, alice, dave);
        inbox(carolPost, carol, carol, alice);
        inbox(erinPost, erin, erin, alice);
        inbox(privatePost, alice, alice);
        inbox(alicePost, alice, alice, bob, carol, erin);

        like(bobPost, alice);
        like(bobPost, carol);
        like(alicePost, bob);
        like(alicePost, carol);
        comment(bobPost, alice, "路线发我，周末一起！", now.minusSeconds(50 * 60));
        comment(alicePost, bob, "新朋友报道 🙌", now.minusSeconds(40 * 60));
        comment(alicePost, erin, "这个小圈子很舒服。", now.minusSeconds(30 * 60));

        notification(alice, "FRIEND_REQUEST", dave, "FRIEND_REQUEST", Long.toString(incomingRequest),
                "Dave 请求添加你为好友", null, now.minusSeconds(25 * 60));
        notification(alice, "POST_LIKED", bob, "POST", alicePost,
                "Bob 点赞了你的动态", null, now.minusSeconds(20 * 60));
        notification(alice, "POST_COMMENTED", erin, "POST", alicePost,
                "Erin 评论了你的动态", null, now.minusSeconds(15 * 60));
        notification(alice, "POST_LIKED", carol, "POST", alicePost,
                "Carol 点赞了你的动态", now.minusSeconds(5 * 60), now.minusSeconds(10 * 60));

        log.info("Demo data initialized: 7 users, 5 posts, relationships, engagement and notifications");
    }

    private long user(String username, String nickname, String bio, String role, String passwordHash) {
        jdbc.sql("""
                INSERT INTO users(username, nickname, bio, password_hash, role)
                VALUES (:username, :nickname, :bio, :passwordHash, :role)
                """).param("username", username).param("nickname", nickname).param("bio", bio)
                .param("passwordHash", passwordHash).param("role", role).update();
        return jdbc.sql("SELECT id FROM users WHERE username = :username")
                .param("username", username).query(Long.class).single();
    }

    private void friendship(long first, long second) {
        long low = Math.min(first, second);
        long high = Math.max(first, second);
        jdbc.sql("INSERT INTO friendships(user_low, user_high, status) VALUES (:low, :high, 'ACTIVE')")
                .param("low", low).param("high", high).update();
    }

    private long friendRequest(long requester, long recipient, String status, Instant respondedAt) {
        jdbc.sql("""
                INSERT INTO friend_requests(requester_id, recipient_id, status, responded_at)
                VALUES (:requester, :recipient, :status, :respondedAt)
                """).param("requester", requester).param("recipient", recipient).param("status", status)
                .param("respondedAt", timestamp(respondedAt)).update();
        return jdbc.sql("""
                SELECT id FROM friend_requests
                 WHERE pair_low = LEAST(:requester, :recipient)
                   AND pair_high = GREATEST(:requester, :recipient)
                """).param("requester", requester).param("recipient", recipient).query(Long.class).single();
    }

    private void post(String id, long author, String idempotencyKey, String fingerprintChar,
                      String content, String visibility, Instant publishedAt) {
        jdbc.sql("""
                INSERT INTO posts(id, author_id, idempotency_key, request_fingerprint, content,
                                  visibility, status, published_at)
                VALUES (:id, :author, :idempotencyKey, :fingerprint, :content,
                        :visibility, 'ACTIVE', :publishedAt)
                """).param("id", id).param("author", author).param("idempotencyKey", idempotencyKey)
                .param("fingerprint", fingerprintChar.repeat(64)).param("content", content)
                .param("visibility", visibility).param("publishedAt", timestamp(publishedAt)).update();
    }

    private void acl(String postId, long targetUserId, String ruleType) {
        jdbc.sql("INSERT INTO post_acl(post_id, target_user_id, rule_type) VALUES (:post, :target, :rule)")
                .param("post", postId).param("target", targetUserId).param("rule", ruleType).update();
    }

    private void inbox(String postId, long authorId, long... ownerIds) {
        for (long ownerId : ownerIds) {
            jdbc.sql("""
                    INSERT INTO feed_inbox(owner_id, post_id, author_id, published_at)
                    SELECT :owner, id, author_id, published_at FROM posts WHERE id = :post
                    """).param("owner", ownerId).param("post", postId).update();
        }
    }

    private void like(String postId, long userId) {
        jdbc.sql("INSERT INTO post_likes(post_id, user_id) VALUES (:post, :user)")
                .param("post", postId).param("user", userId).update();
    }

    private void comment(String postId, long authorId, String content, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO post_comments(post_id, author_id, content, status, created_at, updated_at)
                VALUES (:post, :author, :content, 'ACTIVE', :createdAt, :createdAt)
                """).param("post", postId).param("author", authorId).param("content", content)
                .param("createdAt", timestamp(createdAt)).update();
    }

    private void notification(long userId, String type, Long actorId, String entityType,
                              String entityId, String message, Instant readAt, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO notifications(user_id, type, actor_id, entity_type, entity_id,
                                          message, read_at, created_at)
                VALUES (:user, :type, :actor, :entityType, :entityId, :message, :readAt, :createdAt)
                """).param("user", userId).param("type", type).param("actor", actorId)
                .param("entityType", entityType).param("entityId", entityId).param("message", message)
                .param("readAt", timestamp(readAt)).param("createdAt", timestamp(createdAt)).update();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

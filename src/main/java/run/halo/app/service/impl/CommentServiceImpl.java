package run.halo.app.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.util.HtmlUtils;
import run.halo.app.event.comment.CommentNewEvent;
import run.halo.app.event.comment.CommentPassEvent;
import run.halo.app.event.comment.CommentReplyEvent;
import run.halo.app.exception.NotFoundException;
import run.halo.app.model.dto.post.PostMinimalOutputDTO;
import run.halo.app.model.entity.Comment;
import run.halo.app.model.entity.Post;
import run.halo.app.model.entity.User;
import run.halo.app.model.enums.CommentStatus;
import run.halo.app.model.params.CommentParam;
import run.halo.app.model.params.CommentQuery;
import run.halo.app.model.projection.CommentCountProjection;
import run.halo.app.model.properties.BlogProperties;
import run.halo.app.model.properties.CommentProperties;
import run.halo.app.model.support.CommentPage;
import run.halo.app.model.vo.CommentVO;
import run.halo.app.model.vo.CommentWithParentVO;
import run.halo.app.model.vo.CommentWithPostVO;
import run.halo.app.repository.CommentRepository;
import run.halo.app.repository.PostRepository;
import run.halo.app.security.authentication.Authentication;
import run.halo.app.security.context.SecurityContextHolder;
import run.halo.app.service.CommentService;
import run.halo.app.service.OptionService;
import run.halo.app.service.base.AbstractCrudService;
import run.halo.app.utils.ServiceUtils;
import run.halo.app.utils.ServletUtils;
import run.halo.app.utils.ValidationUtils;

import javax.persistence.criteria.Predicate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CommentService implementation class
 *
 * @author : RYAN0UP
 * @date : 2019-03-14
 */
@Slf4j
@Service
public class CommentServiceImpl extends AbstractCrudService<Comment, Long> implements CommentService {

    private final CommentRepository commentRepository;

    private final PostRepository postRepository;

    private final OptionService optionService;

    private final ApplicationEventPublisher eventPublisher;

    public CommentServiceImpl(CommentRepository commentRepository,
                              PostRepository postRepository,
                              OptionService optionService,
                              ApplicationEventPublisher eventPublisher) {
        super(commentRepository);
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.optionService = optionService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Page<CommentWithPostVO> pageLatest(int top) {
        Assert.isTrue(top > 0, "Top number must not be less than 0");

        // Build page request
        PageRequest latestPageable = PageRequest.of(0, top, Sort.by(Sort.Direction.DESC, "createTime"));

        return convertBy(listAll(latestPageable));
    }

    @Override
    public Page<CommentWithPostVO> pageBy(CommentStatus status, Pageable pageable) {
        Assert.notNull(status, "Comment status must not be null");
        Assert.notNull(pageable, "Page info must not be null");

        // Find all
        Page<Comment> commentPage = commentRepository.findAllByStatus(status, pageable);

        return convertBy(commentPage);
    }

    @Override
    public Page<CommentWithPostVO> pageBy(CommentQuery commentQuery, Pageable pageable) {
        Assert.notNull(commentQuery, "Comment query must not be null");
        Assert.notNull(pageable, "Page info must not be null");
        Page<Comment> commentPage = commentRepository.findAll(buildSpecByQuery(commentQuery), pageable);
        return convertBy(commentPage);
    }

    @NonNull
    private Specification<Comment> buildSpecByQuery(@NonNull CommentQuery commentQuery) {
        Assert.notNull(commentQuery, "Comment query must not be null");

        return (Specification<Comment>) (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new LinkedList<>();

            if (commentQuery.getStatus() != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), commentQuery.getStatus()));
            }

            if (commentQuery.getKeyword() != null) {

                String likeCondition = String.format("%%%s%%", StringUtils.strip(commentQuery.getKeyword()));

                Predicate authorLike = criteriaBuilder.like(root.get("author"), likeCondition);
                Predicate contentLike = criteriaBuilder.like(root.get("content"), likeCondition);
                Predicate emailLike = criteriaBuilder.like(root.get("email"), likeCondition);

                predicates.add(criteriaBuilder.or(authorLike, contentLike, emailLike));
            }

            return query.where(predicates.toArray(new Predicate[0])).getRestriction();
        };
    }

    @Override
    public List<Comment> listBy(Integer postId) {
        Assert.notNull(postId, "Post id must not be null");

        return commentRepository.findAllByPostId(postId);
    }

    @Override
    public Map<Integer, Long> countByPostIds(Collection<Integer> postIds) {
        if (CollectionUtils.isEmpty(postIds)) {
            return Collections.emptyMap();
        }

        // Get all comment counts
        List<CommentCountProjection> commentCountProjections = commentRepository.countByPostIds(postIds);

        return ServiceUtils.convertToMap(commentCountProjections, CommentCountProjection::getPostId, CommentCountProjection::getCount);
    }

    @Override
    public Comment createBy(CommentParam commentParam) {
        Assert.notNull(commentParam, "Comment param must not be null");

        // Check user login status and set this field
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null) {
            User user = authentication.getDetail().getUser();
            commentParam.setAuthor(StringUtils.isEmpty(user.getNickname()) ? user.getUsername() : user.getNickname());
            commentParam.setEmail(user.getEmail());
            commentParam.setAuthorUrl(optionService.getByPropertyOfNullable(BlogProperties.BLOG_URL));
        }

        // Validate the comment param manually
        ValidationUtils.validate(commentParam);

        // Check post id
        boolean isPostExist = postRepository.existsById(commentParam.getPostId());
        if (!isPostExist) {
            throw new NotFoundException("The post with id " + commentParam.getPostId() + " was not found");
        }

        // Check parent id
        if (!ServiceUtils.isEmptyId(commentParam.getParentId())) {
            mustExistById(commentParam.getParentId());
        }

        // Convert to comment
        Comment comment = commentParam.convertTo();

        // Set some default values
        comment.setIpAddress(ServletUtils.getRequestIp());
        comment.setUserAgent(ServletUtils.getHeaderIgnoreCase(HttpHeaders.USER_AGENT));
        comment.setGavatarMd5(DigestUtils.md5Hex(comment.getEmail()));

        if (authentication != null) {
            // Comment of blogger
            comment.setIsAdmin(true);
            comment.setStatus(CommentStatus.PUBLISHED);
        } else {
            // Comment of guest
            // Handle comment status
            Boolean needAudit = optionService.getByPropertyOrDefault(CommentProperties.NEW_NEED_CHECK, Boolean.class, true);
            comment.setStatus(needAudit ? CommentStatus.AUDITING : CommentStatus.PUBLISHED);
        }

        // Create comment
        Comment createdComment = create(comment);

        if (ServiceUtils.isEmptyId(createdComment.getParentId())) {
            if (authentication == null) {
                // New comment of guest
                eventPublisher.publishEvent(new CommentNewEvent(this, createdComment.getId()));
            }
        } else {
            // Reply comment
            eventPublisher.publishEvent(new CommentReplyEvent(this, createdComment.getId()));
        }

        return createdComment;
    }

    @Override
    public Page<CommentVO> pageVosBy(Integer postId, Pageable pageable) {
        Assert.notNull(postId, "Post id must not be null");
        Assert.notNull(pageable, "Page info must not be null");

        log.debug("Getting comment tree view of post: [{}], page info: [{}]", postId, pageable);

        // List all the top comments (Caution: This list will be cleared)
        List<Comment> comments = commentRepository.findAllByPostIdAndStatus(postId, CommentStatus.PUBLISHED);

        // Init the top virtual comment
        CommentVO topVirtualComment = new CommentVO();
        topVirtualComment.setId(0L);
        topVirtualComment.setChildren(new LinkedList<>());

        // Concrete the comment tree
        concreteTree(topVirtualComment, new LinkedList<>(comments), buildCommentComparator(pageable.getSortOr(Sort.by(Sort.Direction.DESC, "createTime"))));

        List<CommentVO> topComments = topVirtualComment.getChildren();

        List<CommentVO> pageContent;

        // Calc the shear index
        int startIndex = pageable.getPageNumber() * pageable.getPageSize();
        if (startIndex >= topComments.size() || startIndex < 0) {
            pageContent = Collections.emptyList();
        } else {
            int endIndex = startIndex + pageable.getPageSize();
            if (endIndex > topComments.size()) {
                endIndex = topComments.size();
            }

            log.debug("Top comments size: [{}]", topComments.size());
            log.debug("Start index: [{}]", startIndex);
            log.debug("End index: [{}]", endIndex);

            pageContent = topComments.subList(startIndex, endIndex);
        }

        return new CommentPage<>(pageContent, pageable, topComments.size(), comments.size());
    }

    @Override
    public Page<CommentWithParentVO> pageWithParentVoBy(Integer postId, Pageable pageable) {
        Assert.notNull(postId, "Post id must not be null");
        Assert.notNull(pageable, "Page info must not be null");

        log.debug("Getting comment list view of post: [{}], page info: [{}]", postId, pageable);

        // List all the top comments (Caution: This list will be cleared)
        Page<Comment> commentPage = commentRepository.findAllByPostIdAndStatus(postId, CommentStatus.PUBLISHED, pageable);

        // Get all comments
        List<Comment> comments = commentPage.getContent();

        // Get all comment parent ids
        Set<Long> parentIds = ServiceUtils.fetchProperty(comments, Comment::getParentId);

        // Get all parent comments
        List<Comment> parentComments = commentRepository.findAllByIdIn(parentIds, pageable.getSort());

        // Convert to comment map (Key: comment id, value: comment)
        Map<Long, Comment> parentCommentMap = ServiceUtils.convertToMap(parentComments, Comment::getId);

        Map<Long, CommentWithParentVO> parentCommentVoMap = new HashMap<>(parentCommentMap.size());

        // Convert to comment page
        return commentPage.map(comment -> {
            // Convert to with parent vo
            CommentWithParentVO commentWithParentVO = new CommentWithParentVO().convertFrom(comment);

            // Get parent comment vo from cache
            CommentWithParentVO parentCommentVo = parentCommentVoMap.get(comment.getParentId());

            if (parentCommentVo == null) {
                // Get parent comment
                Comment parentComment = parentCommentMap.get(comment.getParentId());

                if (parentComment != null) {
                    // Convert to parent comment vo
                    parentCommentVo = new CommentWithParentVO().convertFrom(parentComment);
                    // Cache the parent comment vo
                    parentCommentVoMap.put(parentComment.getId(), parentCommentVo);
                }
            }

            // Set parent
            commentWithParentVO.setParent(parentCommentVo == null ? null : parentCommentVo.clone());

            return commentWithParentVO;
        });
    }

    @Override
    public Comment updateStatus(Long commentId, CommentStatus status) {
        Assert.notNull(commentId, "Comment id must not be null");
        Assert.notNull(status, "Comment status must not be null");

        // Get comment by id
        Comment comment = getById(commentId);

        // Set comment status
        comment.setStatus(status);

        // Update comment
        Comment updatedComment = update(comment);

        if (CommentStatus.PUBLISHED.equals(status)) {
            // Pass a comment
            eventPublisher.publishEvent(new CommentPassEvent(this, commentId));
        }

        return updatedComment;
    }

    /**
     * Builds a comment comparator.
     *
     * @param sort sort info
     * @return comment comparator
     */
    private Comparator<CommentVO> buildCommentComparator(Sort sort) {
        return (currentComment, toCompareComment) -> {
            Assert.notNull(currentComment, "Current comment must not be null");
            Assert.notNull(toCompareComment, "Comment to compare must not be null");

            // Get sort order
            Order order = sort.filter(anOrder -> anOrder.getProperty().equals("createTime"))
                    .get()
                    .findFirst()
                    .orElseGet(() -> Order.desc("createTime"));

            // Init sign
            int sign = order.getDirection().isAscending() ? 1 : -1;

            // Compare createTime property
            return sign * currentComment.getCreateTime().compareTo(toCompareComment.getCreateTime());
        };
    }

    /**
     * Concretes comment tree.
     *
     * @param parentComment     parent comment vo must not be null
     * @param comments          comment list must not null
     * @param commentComparator comment vo comparator
     */
    private void concreteTree(@NonNull CommentVO parentComment, Collection<Comment> comments, @NonNull Comparator<CommentVO> commentComparator) {
        Assert.notNull(parentComment, "Parent comment must not be null");
        Assert.notNull(commentComparator, "Comment comparator must not be null");

        if (CollectionUtils.isEmpty(comments)) {
            return;
        }

        List<Comment> children = new LinkedList<>();

        comments.forEach(comment -> {
            if (parentComment.getId().equals(comment.getParentId())) {
                // Stage the child comment
                children.add(comment);

                // Convert to comment vo
                CommentVO commentVO = new CommentVO().convertFrom(comment);

                // Add additional content
                if (commentVO.getParentId() > 0) {
                    // TODO Provide an optional additional content
                    commentVO.setContent(String.format(COMMENT_TEMPLATE, parentComment.getId(), parentComment.getAuthor(), commentVO.getContent()));
                }

                // Init children container
                if (parentComment.getChildren() == null) {
                    parentComment.setChildren(new LinkedList<>());
                }

                parentComment.getChildren().add(commentVO);
            }
        });

        // Remove all children
        comments.removeAll(children);

        if (!CollectionUtils.isEmpty(parentComment.getChildren())) {
            // Recursively concrete the children
            parentComment.getChildren().forEach(childComment -> concreteTree(childComment, comments, commentComparator));
            // Sort the children
            parentComment.getChildren().sort(commentComparator);
        }
    }

    /**
     * Converts to comment vo page.
     *
     * @param commentPage comment page must not be null
     * @return a page of comment vo
     */
    @NonNull
    private Page<CommentWithPostVO> convertBy(@NonNull Page<Comment> commentPage) {
        Assert.notNull(commentPage, "Comment page must not be null");

        return new PageImpl<>(convertBy(commentPage.getContent()), commentPage.getPageable(), commentPage.getTotalElements());
    }

    /**
     * Converts to comment vo list.
     *
     * @param comments comment list
     * @return a list of comment vo
     */
    @NonNull
    private List<CommentWithPostVO> convertBy(@Nullable List<Comment> comments) {
        if (CollectionUtils.isEmpty(comments)) {
            return Collections.emptyList();
        }

        // Fetch goods ids
        Set<Integer> postIds = ServiceUtils.fetchProperty(comments, Comment::getPostId);

        // Get all posts
        Map<Integer, Post> postMap = ServiceUtils.convertToMap(postRepository.findAllById(postIds), Post::getId);

        return comments.stream().map(comment -> {
            // Convert to vo
            CommentWithPostVO commentWithPostVO = new CommentWithPostVO().convertFrom(comment);

            // Get post and set to the vo
            commentWithPostVO.setPost(new PostMinimalOutputDTO().convertFrom(postMap.get(comment.getPostId())));

            return commentWithPostVO;
        }).collect(Collectors.toList());
    }

    private String formatContent(@NonNull String content) {
        return HtmlUtils.htmlEscape(content).replace("&lt;br/&gt;", "<br/>");
    }

}
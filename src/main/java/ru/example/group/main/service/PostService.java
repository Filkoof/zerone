package ru.example.group.main.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.group.main.dto.request.PostRequestDto;
import ru.example.group.main.dto.response.CommonListResponseDto;
import ru.example.group.main.dto.response.CommonResponseDto;
import ru.example.group.main.dto.response.PostResponseDto;
import ru.example.group.main.entity.PostEntity;
import ru.example.group.main.entity.TagEntity;
import ru.example.group.main.entity.enumerated.LikeType;
import ru.example.group.main.entity.enumerated.PostType;
import ru.example.group.main.exception.IdUserException;
import ru.example.group.main.exception.PostsException;
import ru.example.group.main.mapper.PostMapper;
import ru.example.group.main.repository.PostRepository;
import ru.example.group.main.repository.TagRepository;
import ru.example.group.main.repository.UserRepository;
import ru.example.group.main.security.SocialNetUserRegisterService;
import ru.example.group.main.util.UtilZerone;

import javax.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostService {

    private final SocialNetUserRegisterService registerService;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final TagRepository tagRepository;
    private final CommentService commentService;
    private final LikesService likesService;
    private final PostMapper postMapper;
    @Value("${post.time.Life.Auto-Delete}")
    private int postLife;

    public ResponseEntity<CommonResponseDto<PostResponseDto>> addNewPost(
            final PostRequestDto request,
            final long id,
            final long publishDate
    ) throws PostsException {
        var response = new CommonResponseDto<PostResponseDto>();
        var userEntity = userRepository.findById(id).orElseThrow();
        var requestedDateTime = LocalDateTime.ofEpochSecond(publishDate / 1000, 0, ZoneOffset.UTC);
        var dateTimeNow = LocalDateTime.now();
        var publishDateTime = requestedDateTime.isBefore(dateTimeNow) ? dateTimeNow : requestedDateTime;

        var tags = request.getTags() != null ? new HashSet<>(request.getTags().stream().map(tagRepository::findByTag).toList()) : null;
        var postE = postMapper.postRequestToEntity(request, publishDateTime, tags, userEntity);
        postRepository.save(postE);

        response.setData(getPostDtoFromEntity(postE));
        return ResponseEntity.ok(response);
    }

    public CommonListResponseDto<PostResponseDto> getNewsfeed(int offset, int itemPerPage) throws PostsException {
        var statePage = postRepository.findAllPostsWithPagination(UtilZerone.getPagination(itemPerPage, offset));
        return tryCatchPostsExceptionForCommonList(statePage, offset, itemPerPage);
    }

    private CommonListResponseDto<PostResponseDto> tryCatchPostsExceptionForCommonList(Page<PostEntity> postPage, int offset, int itemPerPage) throws PostsException {
        try {
            return CommonListResponseDto.<PostResponseDto>builder()
                    .total((int) postPage.getTotalElements())
                    .perPage(itemPerPage)
                    .offset(offset)
                    .data(postPage.stream().map(this::tryCatchPostsExceptionForPostEntity).toList())
                    .error("")
                    .timestamp(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            throw new PostsException(e.getMessage());
        }
    }

    public CommonListResponseDto<PostResponseDto> getNewsUserId(Long id, int offset) {
        var itemPerPage = postRepository.findAllByUserPost(id) == 0 ? 5 : postRepository.findAllByUserPost(id);
        var statePage = postRepository.findAllPostsUserId(id, UtilZerone.getPagination(itemPerPage, offset));

        return CommonListResponseDto.<PostResponseDto>builder()
                .total((int) statePage.getTotalElements())
                .perPage(itemPerPage)
                .offset(offset)
                .data(statePage.stream().map(this::tryCatchPostsExceptionForPostEntity).toList())
                .error("")
                .timestamp(LocalDateTime.now())
                .build();
    }

    private PostResponseDto tryCatchPostsExceptionForPostEntity(PostEntity entity) {
        try {
            return getPostDtoFromEntity(entity);
        } catch (PostsException e) {
            return null;
        }
    }

    public CommonResponseDto<PostResponseDto> getPostById(Long id) throws PostsException {
        var data = getPostDtoFromEntity(postRepository.findPostEntityById(id));
        return CommonResponseDto.<PostResponseDto>builder()
                .data(data)
                .error("")
                .timeStamp(LocalDateTime.now())
                .build();
    }

    public CommonListResponseDto<Object> getNewsByListUserId(List<Long> listPostId, int offset) throws PostsException {
        var itemPerPage = 0;
        List<Object> postList = new ArrayList<>();
        for (Long postId : listPostId) {
            itemPerPage += postRepository.findAllByUserPost(postId) == 0 ? 5 : postRepository.findAllByUserPost(postId);
            postList.add(getPostDtoFromEntity(postRepository.findPostEntityById(postId)));
        }
        return CommonListResponseDto.builder()
                .total(postList.size())
                .perPage(itemPerPage)
                .offset(offset)
                .data(postList)
                .error("OK")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional
    public ResponseEntity<CommonResponseDto<PostResponseDto>> deletePost(Long id)
            throws EntityNotFoundException, PostsException {
        try {
            var post = postRepository.findById(id).orElseThrow(EntityNotFoundException::new);
            var user = registerService.getCurrentUser();
            if (!post.getUser().getId().equals(user.getId())) {
                throw new IdUserException(
                        "id пользователя не совпадает с id пользователя опубликовавшего данный пост");
            }
            post.setDeleted(true);
            postRepository.saveAndFlush(post);
            var response = new CommonResponseDto<PostResponseDto>();
            response.setTimeStamp(LocalDateTime.now());
            response.setData(getPostDtoFromEntity(post));
            return ResponseEntity.ok(response);
        } catch (EntityNotFoundException | IdUserException e) {
            throw new PostsException(e.getMessage());
        }
    }

    public CommonResponseDto<PostResponseDto> editPost(long id, long publishDate, PostRequestDto request) throws PostsException {
        var date = new Date(publishDate);
        var tags = request.getTags() != null ? new HashSet<>(request.getTags().stream().map(tagRepository::findByTag).toList()) : null;
        var post = postRepository.findPostEntityById(id);
        post.setTitle(request.getTitle());
        post.setPostText(request.getText());
        post.setTagEntities(tags);
        postRepository.save(post);

        return CommonResponseDto.<PostResponseDto>builder()
                .data(getPostDtoFromEntity(post))
                .error("")
                .timeStamp(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
                .build();
    }

    public ResponseEntity<PostResponseDto> recoverPost(Long id) throws PostsException {
        try {
            var post = postRepository.findById(id).orElseThrow(EntityNotFoundException::new);
            var user = registerService.getCurrentUser();
            if (!post.getUser().getId().equals(user.getId())) {
                throw new IdUserException("Id пользователя не совпадает с id пользотеля создавшего пост");
            }
            post.setDeleted(false);
            postRepository.saveAndFlush(post);

            return ResponseEntity.ok(PostResponseDto.builder().build());
        } catch (EntityNotFoundException | IdUserException e) {
            throw new PostsException(e.getMessage());
        }
    }

    @Scheduled(cron = "@daily")
    public void deletePostAfter30Days() {
        postRepository.deletePostEntity(LocalDateTime.now().minusDays(postLife));
    }

    private PostType getType(PostEntity post) {
        if (post.isDeleted()) {
            return PostType.DELETED;
        } else if (post.getTime().isAfter(LocalDateTime.now())) {
            return PostType.QUEUED;
        } else return PostType.POSTED;
    }

    private PostResponseDto getPostDtoFromEntity(PostEntity postEntity) throws PostsException {
        try {
            var tags = postEntity.getTagEntities().stream().map(TagEntity::getTag).toList();
            var type = getType(postEntity);
            var listComment = commentService.getCommonList(postEntity.getId(), 5, 0);
            Integer likesForPostCount = likesService.likesCountByPostIdAndType(postEntity.getId(), LikeType.POST);
            Boolean isMyLike = likesService.isMyLikeByPostOrCommentIdAndTypeAndUserId(postEntity.getId(), LikeType.POST, registerService.getCurrentUser());
            return postMapper.postEntityToDto(postEntity, tags, type, listComment, isMyLike, likesForPostCount);
        } catch (Exception e) {
            throw new PostsException(e.getMessage());
        }
    }
}
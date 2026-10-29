package com.mosquizto.api.service.impl;

import com.mosquizto.api.dto.request.CollectionRequest;
import com.mosquizto.api.dto.response.CollectionResponse;
import com.mosquizto.api.dto.response.PageResponse;
import com.mosquizto.api.exception.AccessDeniedException;
import com.mosquizto.api.exception.ResourceNotFoundException;
import com.mosquizto.api.mapper.CollectionMapper;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.model.User;
import com.mosquizto.api.model.UserCollection;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.repository.UserCollectionRepository;
import com.mosquizto.api.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionServiceImpl implements CollectionService {

    private final CollectionRepository collectionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CollectionMapper collectionMapper;
    private final UserCollectionRepository userCollectionRepository;
    private final UserCollectionService userCollectionService;
    private final CollectionSearchService collectionSearchService;
    private final CollectionMembershipResolver membershipResolver;
    private final EmbeddingService embeddingService ;
    private final VectorStoreService vectorStoreService ;
    @Override
    @Transactional
    public Integer addCollection(CollectionRequest request) {
        User user = this.currentUserProvider.getCurrentUser();
        Collection collection = this.collectionMapper.toEntity(request, user);
        Collection savedCollection = this.collectionRepository.save(collection);

        UserCollection userCollection = UserCollection.createOwner(user, savedCollection);

        this.userCollectionRepository.save(userCollection);
        collectionSearchService.upsert(savedCollection);
        try {
            float[] vector = embeddingService.embedCollection(savedCollection);
            vectorStoreService.upsertCollection(savedCollection, vector);
        } catch (Exception e) {
            log.error("Failed to sync collection to Qdrant: {}", savedCollection.getId(), e);
        }
        return savedCollection.getId();
    }

    @Override
    public PageResponse<CollectionResponse> getMyCollections(int page, int size) {
        User user = this.currentUserProvider.getCurrentUser();


        Page<Collection> collectionPage = this.collectionRepository.findAllAccessibleCollections(
                user.getId(), PageRequest.of(page - 1, size));

        List<CollectionResponse> items = collectionPage.getContent().stream()
                .map(this.collectionMapper::toResponse)
                .toList();

        return PageResponse.<CollectionResponse>builder()
                .page(page)
                .size(size)
                .totalElements(collectionPage.getTotalElements())
                .totalPages(collectionPage.getTotalPages())
                .items(items)
                .build();
    }

    @Override
    public CollectionResponse getDetail(Integer id) {
        Collection collection = this.collectionRepository.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collection not found"));
        User user = this.currentUserProvider.getCurrentUser();
        membershipResolver.requireCanView(collection, user);

        userCollectionService.updateLastOpenedAt(user.getId(), id);
        return this.collectionMapper.toResponse(collection);
    }

    @Override
    public void updateCollection(Integer id, CollectionRequest request) {
        User user = currentUserProvider.getCurrentUser();

        Collection collection = this.collectionRepository.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collection not found"));

        membershipResolver.requireCanEdit(collection, user);

        String previousTitle = collection.getTitle();
        String previousDescription = collection.getDescription();
        Boolean previousVisibility = collection.getVisibility();
        this.collectionMapper.updateEntity(collection, request);
        var updatedCollection = collectionRepository.save(collection);
        collectionSearchService.upsert(updatedCollection);
        if (!Objects.equals(previousDescription, updatedCollection.getDescription())
                || !Objects.equals(previousTitle, updatedCollection.getTitle())
                || !Objects.equals(previousVisibility, updatedCollection.getVisibility()))
        {
            try {
                float[] vector = embeddingService.embedCollection(updatedCollection);
                vectorStoreService.upsertCollection(updatedCollection, vector);
            } catch (Exception e) {
                log.error("Failed to sync collection to Qdrant: {}", updatedCollection.getId(), e);
            }
        }
    }

    @Override
    @Transactional
    public void deleteCollection(Integer id) {
        User user = this.currentUserProvider.getCurrentUser();
        Collection collection = this.collectionRepository.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collection not found"));

        if (collection.isOwnedBy(user)) {
            this.deleteCollectionAsOwner(collection, user);
            return;
        }

        this.leaveCollectionAsMember(collection, user);
    }

    private void deleteCollectionAsOwner(Collection collection, User user) {
        this.userCollectionRepository.findAllMembershipsByCollectionId(collection.getId())
                .forEach(membership -> {
                    membership.delete(user);
                    this.userCollectionRepository.save(membership);
                });

        collection.delete(user);
        this.collectionRepository.save(collection);
        this.runAfterCommit(() -> {
            this.collectionSearchService.delete(collection.getId());
            this.vectorStoreService.deleteCollection(collection.getId());
        });
    }

    private void leaveCollectionAsMember(Collection collection, User user) {
        UserCollection membership = this.membershipResolver.getMembership(user.getId(), collection.getId());
        if (membership == null) {
            throw new AccessDeniedException("You are not a member of this collection");
        }

        membership.delete(user);
        this.userCollectionRepository.save(membership);
    }

    @Override
    public Collection getById(Integer id) {
        return this.collectionRepository.findActiveById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collection Not Found"));
    }

    @Override
    public Collection save(Collection collection) {
        return this.collectionRepository.save(collection);
    }

    @Override
    public PageResponse<CollectionResponse> getAllPublicCollection(int page, int size) {
        var collectionPage = this.collectionRepository.findPublicCollections(PageRequest.of(page - 1, size));
        List<CollectionResponse> items = collectionPage.getContent().stream()
                .map(this.collectionMapper::toResponse)
                .toList();

        return PageResponse.<CollectionResponse>builder()
                .page(page)
                .size(size)
                .totalElements(collectionPage.getTotalElements())
                .totalPages(collectionPage.getTotalPages())
                .items(items)
                .build();
    }

    @Override
    public List<CollectionResponse> getRecentOpenedCollection() {
        var userId = currentUserProvider.getCurrentUser().getId();
        return userCollectionRepository.findRecentActiveByUserId(userId, PageRequest.of(0, 10))
                .stream()
                .filter(uc -> uc.getLastOpenedAt() != null) // Lọc an toàn
                .map(uc -> collectionMapper.toResponse(uc.getCollection()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isAccessible(Integer collectionId) {
        User user = this.currentUserProvider.getCurrentUser();
        Collection collection = this.collectionRepository.findActiveById(collectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Collection not found"));

        UserCollection membership = membershipResolver.getMembership(user.getId(), collectionId);
        return collection.canView(user, membership);
    }

    @Override
    public List<Collection> getCollectionsByIdsIn(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        // Parse String ID từ Qdrant về Integer để query DB
        List<Integer> intIds = ids.stream()
                .map(Integer::parseInt)
                .toList();

        // Query thẳng vào DB, tốc độ chớp nhoáng
        List<Collection> collections = collectionRepository.findByIdIn(intIds);

        return collections ;
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}

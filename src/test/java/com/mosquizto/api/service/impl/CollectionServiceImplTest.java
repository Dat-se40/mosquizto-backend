package com.mosquizto.api.service.impl;

import com.mosquizto.api.mapper.CollectionMapper;
import com.mosquizto.api.dto.request.CollectionRequest;
import com.mosquizto.api.model.Collection;
import com.mosquizto.api.model.User;
import com.mosquizto.api.model.UserCollection;
import com.mosquizto.api.repository.CollectionRepository;
import com.mosquizto.api.repository.UserCollectionRepository;
import com.mosquizto.api.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionServiceImplTest {

    @Mock
    private CollectionRepository collectionRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private CollectionMapper collectionMapper;

    @Mock
    private UserCollectionRepository userCollectionRepository;

    @Mock
    private UserCollectionService userCollectionService;

    @Mock
    private CollectionSearchService collectionSearchService;

    @Mock
    private CollectionMembershipResolver membershipResolver;

    private CollectionServiceImpl collectionService;

    @Mock
    private EmbeddingService embeddingService ;
    @Mock
    private VectorStoreService vectorStoreService ;
    @BeforeEach
    void setUp() {
        this.collectionService = new CollectionServiceImpl(
                this.collectionRepository,
                this.currentUserProvider,
                this.collectionMapper,
                this.userCollectionRepository,
                this.userCollectionService,
                this.collectionSearchService,
                this.membershipResolver,
                this.embeddingService,
                this.vectorStoreService

        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldReembedChangedTextAndRefreshVisibilityAfterMapping() {
        User user = new User();
        user.setId(1L);
        Collection collection = Collection.initialize(user, "Before", null, true);
        collection.setId(7);
        CollectionRequest request = new CollectionRequest();
        request.setTitle("After");
        request.setDescription("Updated");
        request.setVisibility(false);
        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(collectionRepository.findActiveById(7)).thenReturn(Optional.of(collection));
        org.mockito.Mockito.doAnswer(invocation -> {
            collection.updateInfo(request.getTitle(), request.getDescription(), request.getVisibility());
            return null;
        }).when(collectionMapper).updateEntity(collection, request);
        when(collectionRepository.save(collection)).thenReturn(collection);
        float[] vector = new float[384];
        vector[0] = 1;
        when(embeddingService.embedCollection(collection)).thenReturn(vector);

        collectionService.updateCollection(7, request);

        verify(vectorStoreService).upsertCollection(collection, vector);
        verify(collectionSearchService).upsert(collection);
    }

    @Test
    void shouldRefreshQdrantWhenOnlyVisibilityChanges() {
        User user = new User();
        user.setId(1L);
        Collection collection = Collection.initialize(user, "Java", null, true);
        collection.setId(7);
        CollectionRequest request = new CollectionRequest();
        request.setTitle("Java");
        request.setVisibility(false);
        when(currentUserProvider.getCurrentUser()).thenReturn(user);
        when(collectionRepository.findActiveById(7)).thenReturn(Optional.of(collection));
        org.mockito.Mockito.doAnswer(invocation -> {
            collection.updateInfo(request.getTitle(), request.getDescription(), request.getVisibility());
            return null;
        }).when(collectionMapper).updateEntity(collection, request);
        when(collectionRepository.save(collection)).thenReturn(collection);
        float[] vector = new float[384]; vector[0] = 1;
        when(embeddingService.embedCollection(collection)).thenReturn(vector);

        collectionService.updateCollection(7, request);

        verify(vectorStoreService).upsertCollection(collection, vector);
    }

    @Test
    void shouldSoftDeleteCollectionAndAllMembershipsAfterCommitWhenOwnerDeletes() {
        User user = new User();
        user.setId(1L);
        Collection collection = Collection.initialize(user, "Java", "Backend", true);
        collection.setId(7);
        UserCollection ownerMembership = UserCollection.createOwner(user, collection);
        when(this.currentUserProvider.getCurrentUser()).thenReturn(user);
        when(this.collectionRepository.findActiveById(7)).thenReturn(Optional.of(collection));
        when(this.userCollectionRepository.findAllMembershipsByCollectionId(7))
                .thenReturn(List.of(ownerMembership));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        this.collectionService.deleteCollection(7);

        assertNotNull(collection.getDeletedAt());
        assertSame(user, collection.getDeletedBy());
        assertNotNull(ownerMembership.getDeletedAt());
        verify(this.collectionRepository).save(collection);
        verify(this.userCollectionRepository).save(ownerMembership);
        verify(this.collectionSearchService, never()).delete(7);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(this.collectionSearchService).delete(7);
    }

    @Test
    void shouldOnlySoftDeleteMembershipWhenNonOwnerLeavesCollection() {
        User owner = new User();
        owner.setId(1L);
        User member = new User();
        member.setId(2L);
        Collection collection = Collection.initialize(owner, "Java", "Backend", true);
        collection.setId(7);
        UserCollection membership = UserCollection.createShareInvite(member, collection, com.mosquizto.api.util.CollectionRole.VIEWER, owner);
        membership.approve();

        when(this.currentUserProvider.getCurrentUser()).thenReturn(member);
        when(this.collectionRepository.findActiveById(7)).thenReturn(Optional.of(collection));
        when(this.membershipResolver.getMembership(2L, 7)).thenReturn(membership);

        this.collectionService.deleteCollection(7);

        assertNotNull(membership.getDeletedAt());
        assertSame(member, membership.getDeletedBy());
        verify(this.userCollectionRepository).save(membership);
        verify(this.collectionRepository, never()).save(collection);
        verify(this.collectionSearchService, never()).delete(7);
        verify(this.vectorStoreService, never()).deleteCollection(7);
    }
}

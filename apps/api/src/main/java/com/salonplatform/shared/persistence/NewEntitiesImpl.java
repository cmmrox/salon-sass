package com.salonplatform.shared.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The implementation Spring Data attaches to every repository that extends {@link NewEntities}. Spring Data
 * finds a fragment's implementation by its interface's name with the suffix {@code Impl}, so this class has no
 * name of its own.
 */
class NewEntitiesImpl<T> implements NewEntities<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public <S extends T> S persist(S entity) {
        entityManager.persist(entity);
        return entity;
    }
}

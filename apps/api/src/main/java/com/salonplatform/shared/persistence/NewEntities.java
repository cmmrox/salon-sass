package com.salonplatform.shared.persistence;

/**
 * A repository fragment that creates entities: {@link #persist} always inserts, never merges. Spring Data's
 * {@code save} treats an entity with an assigned id and no {@code @Version} as one that already exists and
 * merges it, which costs a select and hands back a different instance than the one the caller goes on
 * changing. A repository of such an entity extends this fragment and creates through it.
 *
 * @param <T> the repository's entity
 */
public interface NewEntities<T> {

    /** Makes a new entity managed; it is inserted when the transaction flushes. */
    <S extends T> S persist(S entity);
}

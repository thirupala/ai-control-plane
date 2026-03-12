package com.decisionmesh.eventsourcing.replay;

import com.decisionmesh.domain.event.DomainEvent;
import java.util.List;

public interface ReplayService<T> {

    T rehydrate(List<DomainEvent> events);

}
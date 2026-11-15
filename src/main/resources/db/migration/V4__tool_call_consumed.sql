-- A proposal can feed at most one continuation, so an old card cannot append another answer.

alter table tool_calls
    add column continued boolean not null default false;

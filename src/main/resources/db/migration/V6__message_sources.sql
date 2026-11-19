-- The indexed files each reply drew on, so the chat can show its sources under the answer.
-- Stored on the message itself (see MessageSourceListConverter) rather than in a join table: a
-- citation is history, and it must not disappear when the document is re-indexed or removed.

alter table messages
    add column sources text;

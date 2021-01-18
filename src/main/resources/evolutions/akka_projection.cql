
-- CREATE KEYSPACE IF NOT EXISTS messages_service_akka_projection WITH REPLICATION = { 'class' : 'SimpleStrategy','replication_factor':1 };

CREATE KEYSPACE IF NOT EXISTS messages_service_akka_projection WITH replication = {'class': 'NetworkTopologyStrategy', 'dc1' : 3 };

CREATE TABLE IF NOT EXISTS messages_service_akka_projection.offset_store (
  projection_name text,
  partition int,
  projection_key text,
  offset text,
  manifest text,
  last_updated timestamp,
  PRIMARY KEY ((projection_name, partition), projection_key));
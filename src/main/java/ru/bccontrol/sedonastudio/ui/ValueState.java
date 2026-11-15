package ru.bccontrol.sedonastudio.ui;

/** Where a value shown on the diagram comes from and how far it can be trusted. */
enum ValueState {
  /** The block exists only in the project. */
  LOCAL,
  /** Changed in the draft and not applied yet. */
  PENDING,
  /** Not connected: the last value read. */
  OFFLINE,
  /** The last read of this block failed. */
  ERROR,
  /** Read from the device, but no longer refreshed in time. */
  STALE,
  /** Read from the device recently. */
  FRESH
}

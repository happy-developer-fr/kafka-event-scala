package fr.happy.developer.domain.event

abstract class Event[V](value: V) {

  override def toString: String = {
    s"Event : ${this.getClass.getName} value: [$value]"
  }
}



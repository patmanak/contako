package com.patmanak.contako.domain.model

/** Only explicit service denial disables an operation; unknown availability stays usable. */
enum class GroupOperation { CREATE, UPDATE, DELETE, ASSIGN_EMAILS }

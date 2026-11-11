package com.company.oa;

import java.lang.annotation.*;

/** Enforced on the resolved MVC handler, including encoded URLs and matrix parameters. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@interface AdminOnly {}

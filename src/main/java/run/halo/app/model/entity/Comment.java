package run.halo.app.model.entity;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

/**
 * Comment entity.
 *
 * @author johnniang
 */
@Entity(name = "Comment")
@DiscriminatorValue("0")
public class Comment extends BaseComment {

}
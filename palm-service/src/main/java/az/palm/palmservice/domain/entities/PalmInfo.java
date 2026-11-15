package az.palm.palmservice.domain.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;

@Entity
@Table(name = "PALM_INFO")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
public class PalmInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Integer palmType;
    private Integer irFeatureSize;
    private Integer rgbFeatureSize;
    @Column(columnDefinition = "text")
    private String irFeatureBase64;
    @Column(columnDefinition = "text")
    private String rgbFeatureBase64;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        PalmInfo palmInfo = (PalmInfo) o;
        return getId() != null && Objects.equals(getId(), palmInfo.getId());
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}

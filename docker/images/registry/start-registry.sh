#!/usr/bin/env bash

REGISTRY_CONFIG="${REGISTRY_HOME}"/conf/registry.yaml

die() {
    echo "${@}"
    exit 1
}

# Push a new directory on to the bash directory stack, or exit with a failure message.
#
# $1: The directory push on to the directory stack.
must_pushd() {
    local target_dir="${1}"
    pushd -- "${target_dir}" &> /dev/null || die "failed to change directory to ${target_dir}"
}

# Pop a directory from the bash directory stack, or exit with a failure message.
must_popd() {
    popd &> /dev/null || die "failed to popd"
}

update_config() {
    local db_type="${DB_TYPE}"
    local data_src_class_name="${DATA_SRC_CLASS_NAME}"
    local db_url="${DB_URL}"
    local db_user="${DB_USER}"
    local db_password="${DB_PASSWORD}"
    local principal="HTTP/$(hostname -f)"
    local keytab="/etc/registry/secrets/http.keytab"

    if [[ "${IS_SECURED}" == "yes" ]]; then
        uncomment '/servletFilters/,+7' $REGISTRY_CONFIG
    fi

    sed -r -i -e "/db.type/ s/:.*/: \"${db_type//\//\\\/}\"/"                   \
        -e "/dataSourceClassName/ s/:.*/: \"${data_src_class_name//\//\\\/}\"/" \
        -e "/dataSource.url/ s/:.*/: \"${db_url//\//\\\/}\"/"                   \
        -e "/dataSource.user/ s/:.*/: \"${db_user//\//\\\/}\"/"                 \
        -e "/dataSource.password/ s/:.*/: \"${db_password//\//\\\/}\"/" \
        -e "/kerberos.principal/ s/:.*/: \"${principal//\//\\\/}\"/" \
        -e "/kerberos.keytab/ s/:.*/: \"${keytab//\//\\\/}\"/" \
        "${REGISTRY_CONFIG}"
}

run_bootstrap() {
    must_pushd "${REGISTRY_HOME}"/bootstrap
    echo "Bootstrap dir : " $PWD
    "./bootstrap-storage.sh" migrate
    if [[ $? -ne 0 ]]; then
        die "Bootstrap script failed!"
    else
        echo "Bootstrap script succeeded!"
    fi
    must_popd
}

run_registry() {
    must_pushd "${REGISTRY_HOME}"/bin
    "./registry" start "${@}"
    if [[ $? -ne 0 ]]; then
        die "Starting the Schema Registry failed!"
    else
        echo "Schema Registry started successfully!"
    fi
    must_popd
}

uncomment() {
    sed -i "$1"' s/^#//' "$2"
}

update_config
run_bootstrap
run_registry "${@}"
